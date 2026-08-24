package binstaller.core

import binstaller.config.Diagnostics
import binstaller.config.ToolName

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import upickle.default.*

import binstaller.core.ToolNameCodec.given

/** Expected lock-file write failure. */
enum LockFileError:
  case Missing(path: Path)
  case ReadFailed(path: Path, message: String)
  case DecodeFailed(path: Path, message: String)
  case WriteFailed(path: Path, message: String)

/** Rendering helpers for lock-file failures. */
object LockFileError:

  /** Render a lock-file failure into a concise user-facing line. */
  def render(error: LockFileError): String = error match
    case LockFileError.Missing(path) =>
      s"lock file $path is missing; run `binstaller lock --config <file>` first or omit --locked"
    case LockFileError.ReadFailed(path, message)   => s"lock read failed for $path: $message"
    case LockFileError.DecodeFailed(path, message) => s"lock decode failed for $path: $message"
    case LockFileError.WriteFailed(path, message)  => s"lock write failed for $path: $message"

/** Runtime options specific to the `lock` command. */
final case class LockOptions(outputPath: String)

/** Lock command option defaults. */
object LockOptions:
  /** Default lock file path shared by `lock` and `apply --locked`. */
  val defaultOutputPath: String = "binstaller.lock.json"

/** Serialized lock file tied to one profile and manifest fingerprint. */
final case class LockFile(
    schemaVersion: Int,
    profileName: String,
    manifestFingerprint: String,
    tools: Vector[LockFileTool]
)

/** Serialized lock metadata for one resolved tool. */
final case class LockFileTool(
    name: ToolName,
    resolvedVersion: Option[String],
    versionProvenance: Option[UrlProvenance],
    downloadProvenance: UrlProvenance,
    sizeBytes: Option[Long],
    checksum: Option[LockFileChecksum],
    dynamicSource: Boolean
)

/**
 * Where a locked checksum came from.
 *
 * The three fields describing a discovery source can only ever be populated together, so they
 * belong to that case rather than to the record. Previously the lock file carried `source` as a
 * free string alongside three nullable siblings, and `summary` counted the states by comparing
 * string literals — a typo there silently reports zero of a category rather than failing.
 */
enum LockedChecksumSource derives upickle.default.ReadWriter:

  /** Pinned by the manifest author. */
  @upickle.implicits.key("configured")
  case Configured

  /** Fetched from a discovery source published alongside the artifact. */
  @upickle.implicits.key("discovered")
  case Discovered(url: String, file: String, provenance: UrlProvenance)

  /** Observed by downloading the artifact while writing the lock file. */
  @upickle.implicits.key("inspected")
  case Inspected

/** Serialized checksum metadata copied from the manifest or a typed discovery source. */
final case class LockFileChecksum(
    algorithm: String,
    value: String,
    source: LockedChecksumSource
)

/** Lock-file checksum constructors and summaries. */
object LockFileChecksum:

  /** Build a checksum entry pinned by the manifest author. */
  def apply(algorithm: String, value: String): LockFileChecksum =
    LockFileChecksum(algorithm, value, LockedChecksumSource.Configured)

  /** Build a checksum entry observed by downloading the artifact. */
  def inspected(algorithm: String, value: String): LockFileChecksum =
    LockFileChecksum(algorithm, value, LockedChecksumSource.Inspected)

  /** Convert resolved checksum provenance into lock-file metadata. */
  def fromResolved(checksum: ResolvedChecksum): LockFileChecksum = LockFileChecksum(
    checksum.algorithm.value,
    checksum.value.value,
    checksum.source match
      case ResolvedChecksumSource.Configured => LockedChecksumSource.Configured
      case ResolvedChecksumSource.Discovered(url, file, provenance) =>
        LockedChecksumSource.Discovered(url, file, provenance)
  )

  /** Summarize checksum states across lock-file tools. */
  def summary(tools: Vector[LockFileTool]): String =
    val sources    = tools.flatMap(_.checksum).map(_.source)
    val configured = sources.count(_ == LockedChecksumSource.Configured)
    val discovered = sources.count:
      case _: LockedChecksumSource.Discovered => true
      case _                                  => false
    val inspected = sources.count(_ == LockedChecksumSource.Inspected)
    val missing   = tools.count(_.checksum.isEmpty)
    s"configured $configured, discovered $discovered, inspected $inspected, missing $missing"

/** Lock-file JSON codecs and constructors. */
object LockFile:
  /** Current lock-file schema version. */
  val schemaVersion: Int = 1

  /** JSON codec for checksum metadata. */
  given ReadWriter[LockFileChecksum] = macroRW

  /** JSON codec for tool lock entries. */
  given ReadWriter[LockFileTool] = macroRW

  /** JSON codec for complete lock files. */
  given ReadWriter[LockFile] = macroRW

/** Boundary for atomically saving lock files. */
trait LockFileStore:
  /** Load a lock file. */
  def load(path: Path): Either[LockFileError, LockFile]

  /** Persist a lock file atomically where supported by the filesystem. */
  def save(path: Path, lockFile: LockFile): Either[LockFileError, Unit]

/** Lock-file storage constructors. */
object LockFileStore:
  /** NIO-backed lock-file storage. */
  def nio: LockFileStore = NioLockFileStore

private[core] object NioLockFileStore extends LockFileStore:

  def load(path: Path): Either[LockFileError, LockFile] =
    val normalized = path.toAbsolutePath.normalize()
    if !Files.exists(normalized) then Left(LockFileError.Missing(normalized))
    else
      Try(read[LockFile](Files.readString(normalized))) match
        case Success(lockFile)                  => Right(lockFile)
        case Failure(error: upickle.core.Abort) =>
          Left(LockFileError.DecodeFailed(normalized, Diagnostics.describe(error)))
        case Failure(error) =>
          Left(LockFileError.ReadFailed(normalized, Diagnostics.describe(error)))

  def save(path: Path, lockFile: LockFile): Either[LockFileError, Unit] =
    val normalized = path.toAbsolutePath.normalize()
    val parent     = Option(normalized.getParent).getOrElse(Path.of("").toAbsolutePath.normalize())
    val tmp        = parent.resolve(s".${normalized.getFileName}.tmp-${UUID.randomUUID()}")
    Try:
      Files.createDirectories(parent)
      val _ = Files.writeString(
        tmp,
        write(lockFile, indent = 2),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE
      )
      val _ = Files.move(
        tmp,
        normalized,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    match
      case Success(_)     => Right(())
      case Failure(error) =>
        val _ = Files.deleteIfExists(tmp)
        Left(LockFileError.WriteFailed(normalized, Diagnostics.describe(error)))
