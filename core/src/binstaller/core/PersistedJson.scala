package binstaller.core

import binstaller.config.Diagnostics

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import scala.annotation.tailrec
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import upickle.default.Reader
import upickle.default.read

private[core] enum PersistedJsonReadError:
  case ReadFailed(message: String)
  case DecodeFailed(message: String)

private[core] object PersistedJson:

  def load[A: Reader](path: Path): Either[PersistedJsonReadError, Option[A]] =
    if Files.notExists(path) then Right(None)
    else
      Try(read[A](Files.readString(path))) match
        case Success(value) => Right(Some(value))
        // The file can be deleted between the existence check and the read; that is still "absent".
        case Failure(_: NoSuchFileException) => Right(None)
        case Failure(DecodeFailure(message)) => Left(PersistedJsonReadError.DecodeFailed(message))
        case Failure(error) => Left(PersistedJsonReadError.ReadFailed(Diagnostics.describe(error)))

  def writeAtomically(
      path: Path,
      contents: String,
      temporaryPath: (Path, Path) => Path = uniqueTemporaryPath
  ): Either[String, Unit] =
    val parent = Option(path.getParent).getOrElse(Path.of("").toAbsolutePath.normalize())
    val temp   = temporaryPath(parent, path)
    Try:
      Files.createDirectories(parent)
      val _ = Files.writeString(
        temp,
        contents,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE
      )
      val _ = moveReplacing(temp, path)
    match
      case Success(_)     => Right(())
      case Failure(error) =>
        val _ = Try(Files.deleteIfExists(temp))
        Left(Diagnostics.describe(error))

  // Not every filesystem supports an atomic rename; there the move falls back to a plain
  // replacing one, matching the "atomically where supported" contract of the state store.
  private def moveReplacing(temp: Path, path: Path): Path =
    try Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    catch
      case _: AtomicMoveNotSupportedException =>
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)

  private def uniqueTemporaryPath(parent: Path, target: Path): Path =
    parent.resolve(s".${target.getFileName}.tmp-${UUID.randomUUID()}")

  private object DecodeFailure:

    def unapply(error: Throwable): Option[String] = error match
      case _: (upickle.core.Abort | upickle.core.AbortException |
            upickle.core.TraceVisitor.TraceException) =>
        val location = Option(error.getMessage).filter(_.nonEmpty)
        val reason   = Diagnostics.describe(rootCause(error))
        Some(location.filter(_ != reason).fold(reason)(at => s"$reason at $at"))
      case _ => None

    @tailrec
    private def rootCause(error: Throwable): Throwable = Option(error.getCause) match
      case Some(cause) if cause ne error => rootCause(cause)
      case _                             => error
