package binstaller.core

import binstaller.config.ArchiveType
import org.tukaani.xz.XZInputStream

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import scala.collection.mutable
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using

private[core] enum ArchiveEntryKind:
  case File, Directory

private[core] object ArchiveExtractor:
  private[core] val maxExtractedBytes: Long = 1024L * 1024L * 1024L

  // Backstops that bound work independently of the byte budget so a pathological archive cannot
  // stall extraction with millions of tiny members or an unbounded inflation loop.
  private[core] val maxEntries: Int = 65536
  private val extractionTimeBudgetMillis: Long = 60_000L

  private[core] def validateExtractedSize(bytes: Long): Either[String, Unit] =
    if bytes >= 0 && bytes <= maxExtractedBytes then Right(())
    else Left(s"archive exceeds extracted byte limit of $maxExtractedBytes bytes")

  private enum ArchiveKind:
    case Zip, Tar

  def extract(
      archive: ResolvedArchive,
      bytes: Array[Byte],
      stagingDir: Path,
      commandExecutor: CommandExecutor
  ): Either[String, Unit] =
    val _ = commandExecutor // retained for source compatibility with injected filesystem fakes
    archive.original.archiveType match
      case ArchiveType.Zip =>
        streamArchive(archive, stagingDir, ArchiveKind.Zip, () => ByteArrayInputStream(bytes))
      case ArchiveType.TarGz =>
        streamArchive(
          archive,
          stagingDir,
          ArchiveKind.Tar,
          () => GZIPInputStream(ByteArrayInputStream(bytes))
        )
      case ArchiveType.TarXz =>
        streamArchive(
          archive,
          stagingDir,
          ArchiveKind.Tar,
          () => XZInputStream(ByteArrayInputStream(bytes))
        )

  def extractFile(
      archive: ResolvedArchive,
      artifact: Path,
      stagingDir: Path,
      commandExecutor: CommandExecutor
  ): Either[String, Unit] =
    val _ = commandExecutor // retained for source compatibility with injected filesystem fakes
    archive.original.archiveType match
      case ArchiveType.Zip =>
        streamArchive(archive, stagingDir, ArchiveKind.Zip, () => Files.newInputStream(artifact))
      case ArchiveType.TarGz =>
        streamArchive(
          archive,
          stagingDir,
          ArchiveKind.Tar,
          () => GZIPInputStream(Files.newInputStream(artifact))
        )
      case ArchiveType.TarXz =>
        streamArchive(
          archive,
          stagingDir,
          ArchiveKind.Tar,
          () => XZInputStream(Files.newInputStream(artifact))
        )

  // A single budgeted pass. The copy plan is derived from the manifest without touching the
  // archive, then the archive is streamed exactly once. Every entry -- planned or not -- passes
  // through the shared byte budget, so unplanned members can no longer inflate without bound.
  private def streamArchive(
      archive: ResolvedArchive,
      stagingDir: Path,
      kind: ArchiveKind,
      openRaw: () => InputStream
  ): Either[String, Unit] =
    buildPlan(archive, stagingDir).flatMap: plan =>
      Try:
        val run = ExtractionRun(plan, stagingDir)
        kind match
          case ArchiveKind.Zip =>
            Using.resource(ZipInputStream(openRaw()))(zip => streamZipEntries(run, zip))
          case ArchiveKind.Tar =>
            Using.resource(openRaw())(input => streamTarEntries(run, input))
        run.finish()
      match
        case Success(_)     => Right(())
        case Failure(error) => Left(error.getMessage)

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
      if entry.isDirectory then boundedDrain(zip, run.budget)
      else
        run.targetFor(name, isDirectory = false) match
          case Some(target) => copyStream(zip, target, run.budget)
          case None         => boundedDrain(zip, run.budget)
      zip.closeEntry()
      entry = zip.getNextEntry

  private def streamTarEntries(run: ExtractionRun, input: InputStream): Unit =
    readTarEntries(input): (entry, content) =>
      run.beginEntry()
      run.register(entry.name)
      entry.kind match
        case ArchiveEntryKind.Directory =>
          // Directory members carry no payload, but skip through the budget defensively so a
          // bogus size cannot inflate unbounded.
          boundedSkip(content, entry.size, run.budget)
        case ArchiveEntryKind.File =>
          run.targetFor(entry.name, isDirectory = false) match
            case Some(target) => copyBounded(content, target, entry.size, run.budget)
            case None         => boundedSkip(content, entry.size, run.budget)

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
    yield
      val fileTargets = files.map((source, _, target) => source -> target).toMap
      val fileOrigins = files.map((source, origin, _) => source -> origin).toMap
      CopyPlan(fileTargets, fileOrigins, directories)

  // Mutable bookkeeping for one extraction pass. Reproduces the exact invariants and error
  // strings the previous two-pass planner enforced.
  private final class ExtractionRun(plan: CopyPlan, stagingDir: Path):
    val budget: ExtractedByteBudget      = ExtractedByteBudget()
    private val deadline: Long           = System.currentTimeMillis() + extractionTimeBudgetMillis
    private val seenSources              = mutable.HashSet.empty[String]
    private val usedTargets              = mutable.HashSet.empty[Path]
    private val matchedFiles             = mutable.HashSet.empty[String]
    private val matchedDirectories       = mutable.HashSet.empty[String]
    private var entryCount               = 0

    def beginEntry(): Unit =
      entryCount += 1
      if entryCount > maxEntries then
        throw IllegalArgumentException("archive exceeds max entry count")
      if System.currentTimeMillis() > deadline then
        throw IllegalArgumentException("archive extraction exceeded time budget")

    def register(name: String): Unit =
      if !seenSources.add(name) then throw IllegalArgumentException(s"duplicate archive member: $name")

    // Resolve the target a member should be written to, or None when it is not part of the plan.
    // Claims the target so two members mapping to the same path fail with the historical message.
    def targetFor(name: String, isDirectory: Boolean): Option[Path] =
      val resolved: Option[Path] =
        if !isDirectory && plan.fileTargets.contains(name) then
          val _ = matchedFiles.add(name)
          Some(plan.fileTargets(name))
        else if isDirectory then None
        else
          plan.directoryPrefixes.find(prefix => name.startsWith(prefix.prefix)) match
            case Some(prefix) =>
              val _        = matchedDirectories.add(prefix.prefix)
              val relative = name.stripPrefix(prefix.prefix)
              val target   = joinArchivePath(prefix.toRoot, relative)
              resolveInside(stagingDir, target) match
                case Right(path)   => Some(path)
                case Left(message) => throw IllegalArgumentException(message)
            case None => None
      resolved.foreach: target =>
        if !usedTargets.add(target) then
          throw IllegalArgumentException(s"multiple archive members map to $target")
      resolved

    def finish(): Unit =
      plan.fileOrigins.foreach: (source, origin) =>
        if !matchedFiles.contains(source) then
          throw IllegalArgumentException(s"archive member not found: $origin")
      plan.directoryPrefixes.foreach: prefix =>
        if !matchedDirectories.contains(prefix.prefix) then
          throw IllegalArgumentException(s"archive directory not found: ${prefix.origin}")

  private final case class TarEntry(name: String, kind: ArchiveEntryKind, size: Long)

  private def readTarEntries(input: InputStream)(handle: (TarEntry, InputStream) => Unit): Unit =
    var header = readTarBlock(input)
    while header.exists(!_.forall(_ == 0.toByte)) do
      val current = header.get
      val entry   = tarEntry(current)
      handle(entry, input)
      val padding = tarPadding(entry.size)
      val _       = skipFully(input, padding)
      header = readTarBlock(input)

  private def tarEntry(header: Array[Byte]): TarEntry =
    val name     = tarString(header, 0, 100)
    val prefix   = tarString(header, 345, 155)
    val fullName = if prefix.isEmpty then name else s"$prefix/$name"
    val source   = normalizedArchivePath(fullName).fold(
      message => throw IllegalArgumentException(message),
      identity
    )
    val size = tarSize(header, 124, 12)
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
    val size =
      if (first & 0x80) != 0 then decodeBase256(header, offset, length)
      else tarOctal(header, offset, length)
    if size < 0 then throw IllegalArgumentException("tar entry declares a negative size")
    validateExtractedSize(size).left.foreach: message =>
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
    // Archive names are treated as POSIX-like relative paths independent of host OS. Backslash,
    // drive prefixes, absolute roots, controls, and `..` are rejected before copy planning.
    if path.isEmpty then Left("archive path must not be empty")
    else if path == "." then Right(path)
    else if path.exists(_ < ' ') then Left(s"archive path contains control character: $value")
    else if path.contains('\\') then Left(s"archive path contains backslash: $value")
    else if path.matches("^[A-Za-z]:.*") then Left(s"archive path is drive-prefixed: $value")
    else
      val nioPath = Path.of(path)
      if nioPath.isAbsolute then Left(s"archive path is absolute: $value")
      else
        val segments = path.split('/').toVector
        val unsafe   = segments.exists(_ == "..")
        if unsafe then Left(s"archive path escapes staging directory: $value")
        else
          val normalized = segments.filterNot(segment => segment.isEmpty || segment == ".")
          if normalized.isEmpty then Right(".")
          else Right(normalized.mkString("/"))

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

  private def copyStream(
      input: InputStream,
      target: Path,
      budget: ExtractedByteBudget
  ): Unit =
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    Using.resource(Files.newOutputStream(
      target,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )): output =>
      val buffer = Array.ofDim[Byte](8192)
      var count  = input.read(buffer)
      while count != -1 do
        budget.consume(count.toLong)
        output.write(buffer, 0, count)
        count = input.read(buffer)

  private def copyBounded(
      input: InputStream,
      target: Path,
      bytes: Long,
      budget: ExtractedByteBudget
  ): Unit =
    budget.consume(bytes)
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    Using.resource(Files.newOutputStream(
      target,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )): output =>
      val buffer    = Array.ofDim[Byte](8192)
      var remaining = bytes
      while remaining > 0 do
        val count = input.read(buffer, 0, math.min(buffer.length.toLong, remaining).toInt)
        if count == -1 then throw IllegalArgumentException("unexpected end of tar entry")
        output.write(buffer, 0, count)
        remaining = remaining - count

  // Skip a tar member of known length, charging its declared size to the budget up front so a
  // bomb is rejected before it can inflate, and failing loudly if the stream ends early.
  private def boundedSkip(input: InputStream, bytes: Long, budget: ExtractedByteBudget): Unit =
    budget.consume(bytes)
    val buffer    = Array.ofDim[Byte](8192)
    var remaining = bytes
    while remaining > 0 do
      val count = input.read(buffer, 0, math.min(buffer.length.toLong, remaining).toInt)
      if count == -1 then throw IllegalArgumentException("unexpected end of tar entry")
      remaining = remaining - count

  // Drain a stream of unknown length (a zip member), charging every inflated chunk to the budget.
  private def boundedDrain(input: InputStream, budget: ExtractedByteBudget): Unit =
    val buffer = Array.ofDim[Byte](8192)
    var count  = input.read(buffer)
    while count != -1 do
      budget.consume(count.toLong)
      count = input.read(buffer)

  private final class ExtractedByteBudget private ():
    private var consumed = 0L

    def consume(bytes: Long): Unit =
      val next = if bytes < 0 || consumed > Long.MaxValue - bytes then -1L else consumed + bytes
      validateExtractedSize(next).left.foreach: message =>
        throw IllegalArgumentException(message)
      consumed += bytes

  private object ExtractedByteBudget:
    def apply(): ExtractedByteBudget = new ExtractedByteBudget()

  private def skipFully(input: InputStream, bytes: Long): Long =
    var remaining = bytes
    while remaining > 0 do
      val skipped = input.skip(remaining)
      if skipped <= 0 then
        if input.read() == -1 then return remaining
        else remaining = remaining - 1
      else remaining = remaining - skipped
    0L
