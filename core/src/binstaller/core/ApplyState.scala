package binstaller.core

import binstaller.config.ToolName

import java.nio.file.Path
import java.nio.file.InvalidPathException
import upickle.default.*

import binstaller.core.ToolNameCodec.given

/** Expected state-file failures during apply resume. */
enum ApplyStateError:
  case InvalidPath(path: String, message: String)
  case ReadFailed(path: Path, message: String)
  case WriteFailed(path: Path, message: String)
  case DecodeFailed(path: Path, message: String)
  case UnsupportedSchema(path: Path, expected: Int, actual: Int)

  case IncompatibleState(
      path: Path,
      expectedProfileName: String,
      actualProfileName: String,
      expectedFingerprint: String,
      actualFingerprint: String
  )

/** Rendering helpers for expected state-file failures. */
object ApplyStateError:

  /** Render a state failure into a concise user-facing line. */
  def render(error: ApplyStateError): String = error match
    case ApplyStateError.InvalidPath(path, message)  => s"state path '$path' is invalid: $message"
    case ApplyStateError.ReadFailed(path, message)   => s"state read failed for $path: $message"
    case ApplyStateError.WriteFailed(path, message)  => s"state write failed for $path: $message"
    case ApplyStateError.DecodeFailed(path, message) => s"state decode failed for $path: $message"
    case ApplyStateError.UnsupportedSchema(path, expected, actual) =>
      s"state file $path uses schema version $actual, expected $expected; " +
        "rerun with --reset-state to ignore saved state"
    case ApplyStateError.IncompatibleState(
          path,
          expectedProfileName,
          actualProfileName,
          expectedFingerprint,
          actualFingerprint
        ) =>
      s"state file $path does not match this manifest: expected profile '$expectedProfileName' " +
        s"with fingerprint $expectedFingerprint, found profile '$actualProfileName' with " +
        s"fingerprint $actualFingerprint; rerun with --reset-state to ignore saved state"

/** Serialized apply state tied to a profile name and manifest fingerprint. */
final case class ApplyState(
    schemaVersion: Int,
    profileName: String,
    manifestFingerprint: String,
    tools: Vector[ApplyStateTool]
)

/** Typed status for a single tool in the apply state file. */
enum ApplyStateToolStatus(val value: String):
  case Completed extends ApplyStateToolStatus("completed")
  case Failed    extends ApplyStateToolStatus("failed")

/** Apply-state tool status constructors and JSON codec. */
object ApplyStateToolStatus:

  /** Parse a serialized status, or `None` if the string is not one this version writes. */
  def fromString(value: String): Option[ApplyStateToolStatus] = value match
    case Completed.value => Some(Completed)
    case Failed.value    => Some(Failed)
    case _               => None

  given ReadWriter[ApplyStateToolStatus] = readwriter[String].bimap[ApplyStateToolStatus](
    _.value,
    value =>
      fromString(value).getOrElse(
        throw upickle.core.Abort(s"unknown apply state tool status: $value")
      )
  )

/** Serialized status for a single tool in the apply state file. */
final case class ApplyStateTool(
    name: ToolName,
    status: ApplyStateToolStatus,
    installDir: Option[String],
    message: Option[String],
    download: Option[UrlProvenance] = None
)

/** Apply-state JSON codecs and constructors. */
object ApplyState:
  /** Current apply-state schema version. */
  val schemaVersion: Int = 1

  /** JSON codec for individual tool state rows. */
  given ReadWriter[ApplyStateTool] = macroRW

  /** JSON codec for the complete apply state. */
  given ReadWriter[ApplyState] = macroRW

  /** Create an empty state file for a compatible profile and manifest fingerprint. */
  def empty(profileName: String, manifestFingerprint: String): ApplyState = ApplyState(
    schemaVersion,
    profileName,
    manifestFingerprint,
    Vector.empty
  )

/** Boundary for loading and atomically saving apply state. */
trait ApplyStateStore:
  /** Directory that owns relative state filenames. */
  def cwd: Path

  /** Load state if it exists. */
  def load(path: Path): Either[ApplyStateError, Option[ApplyState]]

  /** Persist state atomically where supported by the filesystem. */
  def save(path: Path, state: ApplyState): Either[ApplyStateError, Unit]

/** Apply-state storage constructors. */
object ApplyStateStore:
  /** State store rooted in the process current working directory. */
  def cwd: ApplyStateStore = nio(Path.of("").toAbsolutePath.normalize())

  /** NIO-backed state store rooted in an explicit directory. */
  def nio(directory: Path): ApplyStateStore =
    NioApplyStateStore(directory.toAbsolutePath.normalize())

private[core] object StatePathResolver:

  def resolve(rawPath: String, cwd: Path): Either[ApplyStateError.InvalidPath, Path] =
    if rawPath.trim.isEmpty then invalid(rawPath, "state filename must not be empty")
    else
      try
        val path = Path.of(rawPath)
        if path.isAbsolute then invalid(rawPath, "absolute state paths are not allowed")
        else if path.getNameCount != 1 then
          invalid(rawPath, "state path must be a filename in the current working directory")
        else
          val resolved = cwd.toAbsolutePath.normalize().resolve(path).normalize()
          if resolved.getParent == cwd.toAbsolutePath.normalize() then Right(resolved)
          else invalid(rawPath, "state path must stay in the current working directory")
      catch
        case error: InvalidPathException => invalid(rawPath, error.getReason)

  private def invalid(
      path: String,
      message: String
  ): Either[ApplyStateError.InvalidPath, Path] = Left(ApplyStateError.InvalidPath(path, message))

private[core] final class NioApplyStateStore(val cwd: Path) extends ApplyStateStore:

  def load(path: Path): Either[ApplyStateError, Option[ApplyState]] =
    PersistedJson.load[ApplyState](path).left.map:
      case PersistedJsonReadError.DecodeFailed(message) =>
        ApplyStateError.DecodeFailed(path, message)
      case PersistedJsonReadError.ReadFailed(message) => ApplyStateError.ReadFailed(path, message)

  def save(path: Path, state: ApplyState): Either[ApplyStateError, Unit] =
    PersistedJson.writeAtomically(path, write(state, indent = 2))
      .left.map(message => ApplyStateError.WriteFailed(path, message))
