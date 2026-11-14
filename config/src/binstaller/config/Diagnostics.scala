package binstaller.config

import java.nio.charset.CharacterCodingException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

/**
 * Renders a throwable as a user-facing message that is never the literal "null".
 *
 * `Throwable.getMessage` is nullable, and several exceptions this program actually hits have no
 * message at all: `NullPointerException`, `IOException()` thrown from a closed stream, and a number
 * of `java.nio.file` and `java.net` constructions. Interpolating one of those straight into a
 * diagnostic produces user-facing output like `staging: null` or `download: <url>: null`, which
 * names neither what failed nor why.
 *
 * This lives in `config`, the lowest module, so `core` and `cli` can both use it without adding an
 * edge to the module graph.
 */
object Diagnostics:

  /**
   * Message text for `error`, falling back to the deepest cause's message and finally to its class
   * name.
   *
   * A present message is returned unchanged, with one exception: a `java.nio.file` failure whose
   * message is only a path gains its reason, as in `/opt/apps/.tool.stage-1: permission denied`.
   */
  def describe(error: Throwable): String = Option(error.getMessage) match
    case Some(message) => unstatedFileReason(error).fold(message)(reason => s"$message: $reason")
    case None          => error.getCause match
        case null  => error.getClass.getName
        case cause => describe(cause)

  /**
   * Message for a failed file read whose caller already names the path: the reason alone, where
   * `describe` would repeat the path in front of it.
   */
  def describeReadFailure(error: Throwable): String = error match
    case _: CharacterCodingException => "file is not valid UTF-8 text"
    case other                       => unstatedFileReason(other).getOrElse(describe(other))

  /**
   * Why a `java.nio.file` operation failed, when the exception says so only through its type.
   *
   * Such an exception carries the path as its whole message. Printed as is, a read-only apps
   * directory and a missing file both render as a bare path.
   */
  private def unstatedFileReason(error: Throwable): Option[String] = error match
    case failure: FileSystemException if Option(failure.getReason).isEmpty =>
      failure match
        case _: NoSuchFileException        => Some("no such file")
        case _: AccessDeniedException      => Some("permission denied")
        case _: FileAlreadyExistsException => Some("already exists")
        case _: DirectoryNotEmptyException => Some("directory not empty")
        case _                             => None
    case _ => None
