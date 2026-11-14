package binstaller.core

import binstaller.config.Diagnostics
import binstaller.config.SymlinkPrivilege
import binstaller.config.ValidationError

import java.nio.file.Path
import scala.util.Failure
import scala.util.Success
import scala.util.Try

private[core] object ResolvedPathValidator:

  def stateFile(value: String, path: String): Vector[ValidationError] =
    filename(value, path, "state filename")

  def downloadFilename(value: String, path: String): Vector[ValidationError] =
    filename(value, path, "download filename")

  def archivePath(value: String, path: String, label: String): Vector[ValidationError] =
    relativePath(value, path, label, allowCurrentDirectory = true)

  def installRelativePath(value: String, path: String, label: String): Vector[ValidationError] =
    relativePath(value, path, label, allowCurrentDirectory = false)

  // Checked at plan time with the rule apply uses, because apply reaches its own check only after
  // it has replaced the install. A sudo link lands outside installDir by design, so the two
  // privileges follow different rules.
  def symlinkPath(
      value: String,
      path: String,
      installDir: String,
      privilege: SymlinkPrivilege
  ): Vector[ValidationError] =
    val label = privilege match
      case SymlinkPrivilege.User => "local symlink path"
      case SymlinkPrivilege.Sudo => "sudo symlink path"
    symlinkEnd(value, path, label)(SymlinkPaths.linkPath(Path.of(installDir), value, privilege))

  def symlinkTarget(
      value: String,
      path: String,
      installDir: String
  ): Vector[ValidationError] =
    symlinkEnd(value, path, "symlink target")(SymlinkPaths.target(Path.of(installDir), value))

  /** Call only for a link whose two ends each passed [[symlinkPath]] and [[symlinkTarget]]. */
  def distinctSymlinkEnds(
      symlink: ResolvedSymlink,
      path: String,
      installDir: String
  ): Vector[ValidationError] =
    val root = Path.of(installDir)
    val ends =
      for
        link   <- SymlinkPaths.linkPath(root, symlink.path, symlink.privilege)
        target <- SymlinkPaths.target(root, symlink.target)
      yield SymlinkPaths.distinctEnds(link, target)
    ends match
      case Right(Left(message))       => Vector(ValidationError(path, s"symlink path $message"))
      case Right(Right(())) | Left(_) => Vector.empty

  // `resolved` is by-name so the `Try` also covers an installDir the platform cannot parse.
  private def symlinkEnd(value: String, path: String, label: String)(
      resolved: => Either[String, Path]
  ): Vector[ValidationError] =
    val syntaxErrors = pathSyntax(value, path, label)
    if syntaxErrors.nonEmpty then syntaxErrors
    else
      Try(resolved) match
        case Failure(error) =>
          Vector(ValidationError(path, s"invalid $label: ${Diagnostics.describe(error)}"))
        case Success(Left(message)) => Vector(ValidationError(path, s"$label $message"))
        case Success(Right(_))      => Vector.empty

  def pathSyntax(value: String, path: String, label: String): Vector[ValidationError] =
    PathSyntaxRules.validate(value) match
      case Left(violation) => Vector(ValidationError(path, s"$label ${violation.message}"))
      case Right(())       => Vector.empty

  private def filename(value: String, path: String, label: String): Vector[ValidationError] =
    val syntaxErrors = pathSyntax(value, path, label)
    if syntaxErrors.nonEmpty then syntaxErrors
    else if value.contains('/') then
      Vector(ValidationError(path, s"$label must be a filename, not a path"))
    else if value == "." || value == ".." then
      Vector(ValidationError(path, s"$label must not be a traversal segment"))
    else
      Try(Path.of(value)) match
        case Failure(error) =>
          Vector(ValidationError(path, s"invalid $label: ${Diagnostics.describe(error)}"))
        case Success(file) if file.isAbsolute || file.getNameCount != 1 =>
          Vector(ValidationError(path, s"$label must be a filename in the current directory"))
        case Success(_) => Vector.empty

  private def relativePath(
      value: String,
      path: String,
      label: String,
      allowCurrentDirectory: Boolean
  ): Vector[ValidationError] =
    val syntaxErrors = pathSyntax(value, path, label)
    if syntaxErrors.nonEmpty then syntaxErrors
    else
      RelativeInstallPath.fromString(value, allowCurrentDirectory) match
        case Right(_)      => Vector.empty
        case Left(message) => Vector(ValidationError(path, s"$label $message"))
