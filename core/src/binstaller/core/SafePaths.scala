package binstaller.core

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using

/** Audited filesystem primitives shared by every staging, archive, and symlink boundary. */
private[core] object SafePaths:

  def resolveInside(
      root: Path,
      relative: String,
      allowCurrentDirectory: Boolean = false
  ): Either[String, Path] =
    RelativeInstallPath.fromString(relative, allowCurrentDirectory).flatMap: validated =>
      val normalizedRoot = root.toAbsolutePath.normalize()
      val resolved       = normalizedRoot.resolve(validated.path).normalize()
      if resolved.startsWith(normalizedRoot) then Right(resolved)
      else Left(s"path escapes root: $relative")

  /** Best-effort recursive delete; returns the paths that could not be removed. */
  def deleteRecursively(path: Path): Vector[Path] =
    if !Files.exists(path) then Vector.empty
    else
      // The walk itself throws on a directory it may not list. That tree is residue, not a crash:
      // callers run this after the new install is already live.
      Try(Using.resource(Files.walk(path))(_.iterator().asScala.toVector)) match
        case Failure(_)        => Vector(path)
        case Success(children) => children.sortBy(_.getNameCount).reverse.flatMap: child =>
            Try(Files.deleteIfExists(child)) match
              case Failure(_) => Vector(child)
              case Success(_) => Vector.empty

private[core] object ShellRendering:
  def quote(value: String): String = s"'${value.replace("'", "'\"'\"'")}'"
