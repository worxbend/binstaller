package binstaller.core

import binstaller.config.ArchiveType
import binstaller.config.Diagnostics
import org.tukaani.xz.XZInputStream

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import scala.collection.mutable
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using

private[core] enum ArchiveEntryKind:
  case File, Directory

private[core] final case class ArchiveExtractionLimits(
    maxExtractedBytes: Long,
    maxInflatedBytes: Long,
    maxEntries: Int,
    timeBudgetMillis: Long
)

private[core] object ArchiveExtractor:
  // Bytes actually written to disk (the extracted output).
  private[core] val maxExtractedBytes: Long = 1024L * 1024L * 1024L

  // Bytes inflated from the compressed stream, whether written or merely skipped over to reach the
  // next entry. This is the decompression-bomb guard and is deliberately larger than the extracted
  // limit so a legitimate archive whose *written* output is small but whose unused members total
  // more than 1 GiB still extracts — while a bomb (which inflates at >100x) is still rejected.
  private[core] val maxInflatedBytes: Long = 4L * 1024L * 1024L * 1024L

  // Backstops that bound work independently of the byte budgets so a pathological archive cannot
  // stall extraction with millions of tiny members or an unbounded inflation loop. The time budget
  // is generous: the byte/entry budgets are the real bomb guards, so this only catches degenerate
  // stalls and must not trip on a large legitimate archive extracted on a slow machine.
  private[core] val maxEntries: Int            = 65536
  private val extractionTimeBudgetMillis: Long = 300_000L

  private val defaultLimits = ArchiveExtractionLimits(
    maxExtractedBytes,
    maxInflatedBytes,
    maxEntries,
    extractionTimeBudgetMillis
  )

  /** Shared copy/skip/drain buffer size. Not a tuning knob — just one number instead of four. */
  private val copyBufferBytes: Int = 8192

  // The time budget is sampled inside the streaming loops too, so one pathological member cannot
  // blow the budget between entry boundaries. 1024 chunks of copyBufferBytes is 8 MiB.
  private val deadlineCheckIntervalChunks: Int = 1024

  private[core] def validateExtractedSize(bytes: Long): Either[String, Unit] =
    if bytes >= 0 && bytes <= maxExtractedBytes then Right(())
    else Left(s"archive exceeds extracted byte limit of $maxExtractedBytes bytes")

  private[core] def validateInflatedSize(bytes: Long): Either[String, Unit] =
    if bytes >= 0 && bytes <= maxInflatedBytes then Right(())
    else Left(s"archive exceeds inflated byte limit of $maxInflatedBytes bytes")

  private enum ArchiveKind:
    case Zip, Tar

  /**
   * Extract the manifest's selected members from an archive file into a staging directory.
   *
   * Always streamed from the file: the archive is never held in the JVM heap, so a multi-hundred
   * megabyte release artifact costs a buffer rather than its own size.
   */
  def extractFile(
      archive: ResolvedArchive,
      artifact: Path,
      stagingDir: Path,
      limits: ArchiveExtractionLimits = defaultLimits
  ): Either[String, Unit] = archive.original.archiveType match
    case ArchiveType.Zip => streamArchive(
        archive,
        stagingDir,
        ArchiveKind.Zip,
        () => Files.newInputStream(artifact),
        limits
      )
    case ArchiveType.TarGz => streamArchive(
        archive,
        stagingDir,
        ArchiveKind.Tar,
        () => GZIPInputStream(Files.newInputStream(artifact)),
        limits
      )
    case ArchiveType.TarXz => streamArchive(
        archive,
        stagingDir,
        ArchiveKind.Tar,
        () => XZInputStream(Files.newInputStream(artifact)),
        limits
      )

  // A single budgeted pass. The copy plan is derived from the manifest without touching the
  // archive, then the archive is streamed exactly once. Every entry -- planned or not -- passes
  // through the shared byte budget, so unplanned members can no longer inflate without bound.
  private def streamArchive(
      archive: ResolvedArchive,
      stagingDir: Path,
      kind: ArchiveKind,
      openRaw: () => InputStream,
      limits: ArchiveExtractionLimits
  ): Either[String, Unit] = buildPlan(archive, stagingDir).flatMap: plan =>
    Try:
      val run = ExtractionRun(plan, stagingDir, limits)
      kind match
        case ArchiveKind.Zip =>
          Using.resource(ZipInputStream(openRaw()))(zip => streamZipEntries(run, zip))
        case ArchiveKind.Tar => Using.resource(openRaw())(input => streamTarEntries(run, input))
      run.finish()
    match
      case Success(_)     => Right(())
      case Failure(error) => Left(Diagnostics.describe(error))

  private def streamZipEntries(run: ExtractionRun, zip: ZipInputStream): Unit =
    var entry = zip.getNextEntry
    while entry != null do
      run.beginEntry()
      val name = normalizedArchivePath(entry.getName).fold(
        message => throw IllegalArgumentException(message),
        identity
      )
      run.register(name)
      // Zip entry sizes are advisory and may be absent, so drain the actual inflated bytes and
      // charge the budget per chunk rather than trusting the declared size.
      if entry.isDirectory then boundedDrain(zip, run)
      else
        val targets = run.targetsFor(name)
        if targets.isEmpty then boundedDrain(zip, run)
        else
          copyStream(zip, targets.head, run)
          duplicateTo(targets.head, targets.tail, run.budget)
      zip.closeEntry()
      entry = zip.getNextEntry

  private def streamTarEntries(run: ExtractionRun, input: InputStream): Unit =
    readTarEntries(input, () => run.beginEntry()): (entry, content) =>
      run.register(entry.name)
      entry.kind match
        case ArchiveEntryKind.Directory =>
          // Directory members carry no payload, but skip through the budget defensively so a
          // bogus size cannot inflate unbounded.
          boundedSkip(content, entry.size, run)
        case ArchiveEntryKind.File =>
          val targets = run.targetsFor(entry.name)
          if targets.isEmpty then boundedSkip(content, entry.size, run)
          else
            copyBounded(content, targets.head, entry.size, run)
            duplicateTo(targets.head, targets.tail, run.budget)

  private final case class DirPrefix(prefix: String, toRoot: String, origin: String)

  private final case class CopyPlan(
      fileTargets: Map[String, Path],
      fileOrigins: Map[String, String],
      directoryPrefixes: Vector[DirPrefix]
  )

  // Derive the exact copy plan from the manifest alone. Reuses normalizedArchivePath /
  // resolveInside so source names and target paths get identical validation (and error strings)
  // as before.
  private def buildPlan(archive: ResolvedArchive, stagingDir: Path): Either[String, CopyPlan] =
    for
      files <- collectEither(archive.files.map: mapping =>
        for
          source <- normalizedArchivePath(mapping.from)
          target <- resolveInside(stagingDir, mapping.to)
        yield (source, mapping.from, target))
      directories <- collectEither(archive.directories.map: mapping =>
        normalizedArchivePath(mapping.from).map: source =>
          DirPrefix(s"$source/", mapping.to, mapping.from))
      _ <- rejectDuplicateSources(
        files.map((source, _, _) => source) ++ directories.map(_.prefix.stripSuffix("/"))
      )
    yield
      val fileTargets = files.map((source, _, target) => source -> target).toMap
      val fileOrigins = files.map((source, origin, _) => source -> origin).toMap
      CopyPlan(fileTargets, fileOrigins, directories)

  private def rejectDuplicateSources(sources: Vector[String]): Either[String, Unit] =
    val seen = mutable.HashSet.empty[String]
    sources.find(source => !seen.add(source)) match
      case Some(source) => Left(s"duplicate archive source: $source")
      case None         => Right(())

  // Mutable bookkeeping for one extraction pass. Reproduces the exact invariants and error
  // strings the previous two-pass planner enforced.
  private final class ExtractionRun(
      plan: CopyPlan,
      stagingDir: Path,
      limits: ArchiveExtractionLimits
  ):
    val budget: ExtractedByteBudget = ExtractedByteBudget(limits)

    private val deadlineNanos: Long = System.nanoTime() +
      TimeUnit.MILLISECONDS.toNanos(limits.timeBudgetMillis)

    private val seenSources        = mutable.HashSet.empty[String]
    private val usedTargets        = mutable.HashSet.empty[Path]
    private val matchedFiles       = mutable.HashSet.empty[String]
    private val matchedDirectories = mutable.HashSet.empty[String]
    private var entryCount         = 0

    def beginEntry(): Unit =
      entryCount += 1
      if entryCount > limits.maxEntries then
        throw IllegalArgumentException("archive exceeds max entry count")
      checkDeadline()

    // nanoTime rather than currentTimeMillis: the budget measures elapsed work and must not move
    // with wall-clock adjustments. Subtraction is overflow-safe across a nanoTime wrap.
    def checkDeadline(): Unit = if System.nanoTime() - deadlineNanos > 0L then
      throw IllegalArgumentException("archive extraction exceeded time budget")

    def register(name: String): Unit = if !seenSources.add(name) then
      throw IllegalArgumentException(s"duplicate archive member: $name")

    // Every target a member must be written to: its explicit file mapping (if any) AND one target
    // per directory mapping whose prefix it falls under. A member can be covered by both, matching
    // the previous two-pass planner which planned file and directory targets independently — so we
    // must mark EVERY matching prefix (not stop at the file match) or finish() falsely reports the
    // directory as missing. Each resolved target is claimed so two members colliding on one path
    // fail with the historical message.
    def targetsFor(name: String): Vector[Path] =
      val fileTarget = plan.fileTargets.get(name) match
        case Some(target) =>
          val _ = matchedFiles.add(name)
          Vector(target)
        case None => Vector.empty
      val directoryTargets = plan.directoryPrefixes.flatMap: prefix =>
        if name.startsWith(prefix.prefix) then
          val _        = matchedDirectories.add(prefix.prefix)
          val relative = name.stripPrefix(prefix.prefix)
          resolveInside(stagingDir, joinArchivePath(prefix.toRoot, relative)) match
            case Right(path)   => Vector(path)
            case Left(message) => throw IllegalArgumentException(message)
        else Vector.empty
      val targets = fileTarget ++ directoryTargets
      targets.foreach: target =>
        if !usedTargets.add(target) then
          throw IllegalArgumentException(s"multiple archive members map to $target")
      targets

    def finish(): Unit =
      plan.fileOrigins.foreach: (source, origin) =>
        if !matchedFiles.contains(source) then
          throw IllegalArgumentException(s"archive member not found: $origin")
      plan.directoryPrefixes.foreach: prefix =>
        if !matchedDirectories.contains(prefix.prefix) then
          throw IllegalArgumentException(s"archive directory not found: ${prefix.origin}")

  private final case class TarEntry(name: String, kind: ArchiveEntryKind, size: Long)

  // A path too long for the 100-byte header field is not stored in the member header at all. GNU
  // tar writes it as a preceding pseudo-entry (typeflag 'L', conventionally named
  // "././@LongLink") whose payload is the real name, with 'K' doing the same for a link target;
  // bsdtar and Go's archive/tar instead write a PAX extended header ('x', or 'g' for one that
  // describes the whole archive) carrying a "path" attribute. Either way the payload is read
  // whole, ahead of any byte budget, so it is capped here instead -- far above any real path
  // (PATH_MAX is 4096), far below anything worth allocating.
  private val maxMetadataBytes: Long = 8192L

  private def readTarEntries(
      input: InputStream,
      beginHeader: () => Unit
  )(handle: (TarEntry, InputStream) => Unit): Unit =
    // Metadata read from a preceding header, applied to the member header that follows it.
    var pendingName: Option[String] = None
    var pendingSize: Option[Long]   = None
    var header                      = readTarBlock(input)
    while header.exists(!_.forall(_ == 0.toByte)) do
      val current = header.get
      // Counted before the payload is touched, so a stream of nothing but metadata headers trips
      // the entry-count and time budgets instead of looping forever without reaching a member.
      beginHeader()
      val declared = tarSize(current, 124, 12)
      current(156).toChar match
        case 'L' => pendingName = Some(gnuLongName(readMetadata(input, declared)))
        case 'K' =>
          // A long *link* target. The member it names is a link and is rejected on its own header
          // below; consume the payload so that rejection reports the link, not stray bytes.
          val _ = readMetadata(input, declared)
        case 'x' =>
          val records = paxRecords(readMetadata(input, declared))
          records.get("path").foreach(value => pendingName = Some(value))
          // A member larger than the 12-byte octal size field carries its real length here, so
          // honouring it is what keeps the stream aligned on the next header.
          records.get("size").foreach(value => pendingSize = Some(paxSize(value)))
        case 'g' =>
          // A global header describes the archive rather than the member that follows it, so its
          // records are consumed and dropped.
          val _ = readMetadata(input, declared)
        case _ =>
          val entry = tarEntry(current, pendingSize.getOrElse(declared), pendingName)
          pendingName = None
          pendingSize = None
          handle(entry, input)
          val _ = skipFully(input, tarPadding(entry.size))
      header = readTarBlock(input)

  /** Read a metadata member whole, including its padding. */
  private def readMetadata(input: InputStream, size: Long): Array[Byte] =
    if size > maxMetadataBytes then
      throw IllegalArgumentException(s"tar metadata entry exceeds $maxMetadataBytes bytes")
    val bytes  = Array.ofDim[Byte](size.toInt)
    var offset = 0
    while offset < bytes.length do
      val count = input.read(bytes, offset, bytes.length - offset)
      if count == -1 then throw IllegalArgumentException("unexpected end of tar entry")
      offset = offset + count
    val _ = skipFully(input, tarPadding(size))
    bytes

  private def gnuLongName(payload: Array[Byte]): String =
    new String(payload.takeWhile(_ != 0.toByte), java.nio.charset.StandardCharsets.UTF_8)

  // A PAX extended header is a run of "<length> <key>=<value>\n" records where <length> counts the
  // whole record, its own digits included. Lengths are byte counts, so the payload is walked as
  // bytes and only the key and value are decoded -- measuring a decoded string would mismeasure
  // every record holding a non-ASCII path.
  private def paxRecords(payload: Array[Byte]): Map[String, String] =
    val records = mutable.Map.empty[String, String]
    var offset  = 0
    while offset < payload.length do
      val space = indexOfByte(payload, ' '.toByte, offset)
      if space < 0 then throw malformedPaxRecord
      val length = paxRecordLength(payload, offset, space)
      if length > payload.length - offset then throw malformedPaxRecord
      val end = offset + length
      if payload(end - 1) != '\n'.toByte then throw malformedPaxRecord
      val body   = payload.slice(space + 1, end - 1)
      val equals = indexOfByte(body, '='.toByte, 0)
      if equals < 0 then throw malformedPaxRecord
      val key   = new String(body, 0, equals, java.nio.charset.StandardCharsets.UTF_8)
      val value = new String(
        body,
        equals + 1,
        body.length - equals - 1,
        java.nio.charset.StandardCharsets.UTF_8
      )
      val _ = records.put(key, value)
      offset = end
    records.toMap

  // The record's own length prefix, in decimal. It must cover its digits, the space, at least
  // "k=" and the newline, so anything that would not advance past the prefix is malformed.
  private def paxRecordLength(payload: Array[Byte], offset: Int, space: Int): Int =
    if space == offset then throw malformedPaxRecord
    var value = 0
    var index = offset
    while index < space do
      val digit = payload(index) - '0'.toByte
      if digit < 0 || digit > 9 then throw malformedPaxRecord
      if value > (Int.MaxValue - digit) / 10 then throw malformedPaxRecord
      value = value * 10 + digit
      index += 1
    if value <= space - offset + 1 then throw malformedPaxRecord
    value

  private def paxSize(value: String): Long =
    val size =
      try java.lang.Long.parseLong(value.trim)
      catch case _: NumberFormatException => throw malformedPaxRecord
    if size < 0 then throw IllegalArgumentException("tar entry declares a negative size")
    validateInflatedSize(size).left.foreach: message =>
      throw IllegalArgumentException(message)
    size

  private def malformedPaxRecord: IllegalArgumentException =
    IllegalArgumentException("malformed tar extended header record")

  private def indexOfByte(bytes: Array[Byte], value: Byte, from: Int): Int =
    var index = from
    while index < bytes.length && bytes(index) != value do index += 1
    if index == bytes.length then -1 else index

  private def tarEntry(header: Array[Byte], size: Long, longName: Option[String]): TarEntry =
    val fullName = longName match
      case Some(value) => value
      case None        =>
        val name   = tarString(header, 0, 100)
        val prefix = tarString(header, 345, 155)
        if prefix.isEmpty then name else s"$prefix/$name"
    val source = normalizedArchivePath(fullName).fold(
      message => throw IllegalArgumentException(message),
      identity
    )
    val kind = header(156).toChar match
      case 0 | '0' => ArchiveEntryKind.File
      case '5'     => ArchiveEntryKind.Directory
      // Links and special tar metadata are rejected because they can escape the apparent file tree
      // even when the entry name itself is relative.
      case '1' | '2' => throw IllegalArgumentException(s"unsafe archive link entry: $source")
      case other => throw IllegalArgumentException(s"unsupported tar entry type '$other': $source")
    TarEntry(source, kind, size)

  private def readTarBlock(input: InputStream): Option[Array[Byte]] =
    val buffer = Array.ofDim[Byte](512)
    var offset = 0
    while offset < buffer.length do
      val count = input.read(buffer, offset, buffer.length - offset)
      if count == -1 then
        // A clean end-of-stream only occurs on a block boundary. A partial final block means the
        // archive was truncated; treat it as an error rather than parsing garbage as a header.
        if offset == 0 then return None
        else throw IllegalArgumentException("truncated tar archive")
      offset = offset + count
    Some(buffer)

  private def tarString(header: Array[Byte], offset: Int, length: Int): String =
    val bytes = header.slice(offset, offset + length).takeWhile(_ != 0.toByte)
    new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim

  // Decode a tar numeric field. Sizes are normally NUL/space-terminated octal, but GNU tar uses
  // a base-256 big-endian encoding (signalled by the high bit of the first byte) for values that
  // do not fit the octal field. The legacy Long.parseLong path threw NumberFormatException on
  // those headers.
  private def tarSize(header: Array[Byte], offset: Int, length: Int): Long =
    val first = header(offset) & 0xff
    val size  =
      if (first & 0x80) != 0 then decodeBase256(header, offset, length)
      else tarOctal(header, offset, length)
    if size < 0 then throw IllegalArgumentException("tar entry declares a negative size")
    // A declared size bounds how many bytes must be inflated to advance past this member (whether
    // or not it is extracted), so it is checked against the inflated ceiling; the tighter extracted
    // ceiling is enforced at copy time for members actually written.
    validateInflatedSize(size).left.foreach: message =>
      throw IllegalArgumentException(message)
    size

  private def decodeBase256(header: Array[Byte], offset: Int, length: Int): Long =
    // The top bit is the base-256 marker; the next bit is the sign. Negative sizes are nonsense.
    if (header(offset) & 0x40) != 0 then
      throw IllegalArgumentException("tar entry declares a negative size")
    var value = 0L
    var index = 0
    while index < length do
      val raw = if index == 0 then header(offset) & 0x7f else header(offset + index) & 0xff
      if value > (Long.MaxValue >>> 8) then
        throw IllegalArgumentException("tar entry size exceeds supported range")
      value = (value << 8) | raw.toLong
      index += 1
    value

  private def tarOctal(header: Array[Byte], offset: Int, length: Int): Long =
    val value = tarString(header, offset, length).trim
    if value.isEmpty then 0L else java.lang.Long.parseLong(value, 8)

  private def tarPadding(size: Long): Long =
    // size is guaranteed non-negative by tarSize, so this remainder never goes negative.
    val remainder = size % 512L
    if remainder == 0L then 0L else 512L - remainder

  private def normalizedArchivePath(value: String): Either[String, String] =
    val path = value.stripSuffix("/")
    // Archive names are treated as POSIX-like relative paths independent of host OS. The shared
    // syntax rules reject empties, controls, backslashes, drive prefixes, and traversal; absolute
    // roots are rejected below, before copy planning.
    if path == "." then Right(path)
    else
      PathSyntaxRules.validate(path).left.map(archivePathViolation(_, value)).flatMap: _ =>
        if Path.of(path).isAbsolute then Left(s"archive path is absolute: $value")
        else
          val normalized = path.split('/').toVector
            .filterNot(segment => segment.isEmpty || segment == ".")
          if normalized.isEmpty then Right(".")
          else Right(normalized.mkString("/"))

  private def archivePathViolation(violation: PathSyntaxRules.Violation, value: String): String =
    violation match
      case PathSyntaxRules.Violation.Empty             => "archive path must not be empty"
      case PathSyntaxRules.Violation.ControlCharacters =>
        s"archive path contains control character: $value"
      case PathSyntaxRules.Violation.Backslashes       => s"archive path contains backslash: $value"
      case PathSyntaxRules.Violation.DrivePrefixed     => s"archive path is drive-prefixed: $value"
      case PathSyntaxRules.Violation.TraversalSegments =>
        s"archive path escapes staging directory: $value"

  private def resolveInside(root: Path, relative: String): Either[String, Path] =
    val clean = if relative.isEmpty then "." else relative
    validateRelativeTarget(clean).flatMap: _ =>
      SafePaths.resolveInside(root, clean, allowCurrentDirectory = true)

  private def validateRelativeTarget(value: String): Either[String, Unit] =
    if value == "." then Right(())
    else normalizedArchivePath(value).map(_ => ())

  private def collectEither[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
    values.foldLeft(Right(Vector.empty): Either[String, Vector[A]]): (acc, next) =>
      for
        current <- acc
        value   <- next
      yield current :+ value

  private def joinArchivePath(parent: String, child: String): String = parent match
    case "" | "."                     => child
    case value if value.endsWith("/") => s"$value$child"
    case value                        => s"$value/$child"

  private def ensureParent(target: Path): Unit =
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))

  // Create the target's parent directories and open it for a full overwrite. TRUNCATE_EXISTING
  // matters: re-extracting over an older, longer file must not leave that file's tail behind, which
  // would silently produce a corrupt binary rather than a failure.
  private def writingTo(target: Path)(body: OutputStream => Unit): Unit =
    ensureParent(target)
    Using.resource(Files.newOutputStream(
      target,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    ))(body)

  private def copyStream(
      input: InputStream,
      target: Path,
      run: ExtractionRun
  ): Unit = writingTo(target): output =>
    val buffer = Array.ofDim[Byte](copyBufferBytes)
    var count  = input.read(buffer)
    var chunks = 0
    while count != -1 do
      run.budget.extract(count.toLong)
      output.write(buffer, 0, count)
      chunks += 1
      if chunks == deadlineCheckIntervalChunks then
        run.checkDeadline()
        chunks = 0
      count = input.read(buffer)

  private def copyBounded(
      input: InputStream,
      target: Path,
      bytes: Long,
      run: ExtractionRun
  ): Unit =
    // Charged before a single byte is written, so an oversized member is rejected rather than
    // partially extracted.
    run.budget.extract(bytes)
    writingTo(target): output =>
      val buffer    = Array.ofDim[Byte](copyBufferBytes)
      var remaining = bytes
      var chunks    = 0
      while remaining > 0 do
        val count = input.read(buffer, 0, math.min(buffer.length.toLong, remaining).toInt)
        if count == -1 then throw IllegalArgumentException("unexpected end of tar entry")
        output.write(buffer, 0, count)
        remaining = remaining - count
        chunks += 1
        if chunks == deadlineCheckIntervalChunks then
          run.checkDeadline()
          chunks = 0

  // A disk-to-disk duplicate does not inflate the archive again, but it is additional extracted
  // output and must consume the on-disk byte budget.
  private def duplicateTo(
      source: Path,
      extras: Vector[Path],
      budget: ExtractedByteBudget
  ): Unit =
    val bytes = Files.size(source)
    extras.foreach: target =>
      budget.writeCopy(bytes)
      ensureParent(target)
      val _ = Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)

  // Skip a tar member of known length, charging its declared size to the budget up front so a
  // bomb is rejected before it can inflate, and failing loudly if the stream ends early.
  private def boundedSkip(input: InputStream, bytes: Long, run: ExtractionRun): Unit =
    run.budget.inflate(bytes)
    val buffer    = Array.ofDim[Byte](copyBufferBytes)
    var remaining = bytes
    var chunks    = 0
    while remaining > 0 do
      val count = input.read(buffer, 0, math.min(buffer.length.toLong, remaining).toInt)
      if count == -1 then throw IllegalArgumentException("unexpected end of tar entry")
      remaining = remaining - count
      chunks += 1
      if chunks == deadlineCheckIntervalChunks then
        run.checkDeadline()
        chunks = 0

  // Drain a stream of unknown length (a zip member), charging every inflated chunk to the budget.
  private def boundedDrain(input: InputStream, run: ExtractionRun): Unit =
    val buffer = Array.ofDim[Byte](copyBufferBytes)
    var count  = input.read(buffer)
    var chunks = 0
    while count != -1 do
      run.budget.inflate(count.toLong)
      chunks += 1
      if chunks == deadlineCheckIntervalChunks then
        run.checkDeadline()
        chunks = 0
      count = input.read(buffer)

  private final class ExtractedByteBudget private (limits: ArchiveExtractionLimits):
    private var extracted = 0L
    private var inflated  = 0L

    /** Charge bytes that are inflated AND written to disk (a copied member). */
    def extract(bytes: Long): Unit =
      inflate(bytes)
      extracted = plus(extracted, bytes)
      validateExtractedSize(extracted, limits.maxExtractedBytes).left.foreach: message =>
        throw IllegalArgumentException(message)

    /** Charge bytes written by a disk-to-disk copy without charging inflation a second time. */
    def writeCopy(bytes: Long): Unit =
      extracted = plus(extracted, bytes)
      validateExtractedSize(extracted, limits.maxExtractedBytes).left.foreach: message =>
        throw IllegalArgumentException(message)

    /** Charge bytes that are inflated but not written (a skipped or drained member). */
    def inflate(bytes: Long): Unit =
      inflated = plus(inflated, bytes)
      validateInflatedSize(inflated, limits.maxInflatedBytes).left.foreach: message =>
        throw IllegalArgumentException(message)

    private def plus(current: Long, bytes: Long): Long =
      if bytes < 0L || current > Long.MaxValue - bytes then -1L else current + bytes

  private object ExtractedByteBudget:

    def apply(limits: ArchiveExtractionLimits): ExtractedByteBudget =
      new ExtractedByteBudget(limits)

  private def validateExtractedSize(bytes: Long, limit: Long): Either[String, Unit] =
    if bytes >= 0 && bytes <= limit then Right(())
    else Left(s"archive exceeds extracted byte limit of $limit bytes")

  private def validateInflatedSize(bytes: Long, limit: Long): Either[String, Unit] =
    if bytes >= 0 && bytes <= limit then Right(())
    else Left(s"archive exceeds inflated byte limit of $limit bytes")

  private def skipFully(input: InputStream, bytes: Long): Long =
    var remaining = bytes
    while remaining > 0 do
      val skipped = input.skip(remaining)
      if skipped <= 0 then
        if input.read() == -1 then return remaining
        else remaining = remaining - 1
      else remaining = remaining - skipped
    0L
