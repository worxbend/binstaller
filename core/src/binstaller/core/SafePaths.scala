package binstaller.core

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
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

  def deleteRecursively(path: Path): Unit = if Files.exists(path) then
    Using.resource(Files.walk(path)): stream =>
      stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach: child =>
        val _ = Try(Files.deleteIfExists(child))

private[core] object ShellRendering:
  def quote(value: String): String = s"'${value.replace("'", "'\"'\"'")}'"
