package binstaller.core

import binstaller.config.Diagnostics
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

  def externalPath(value: String, path: String, label: String): Vector[ValidationError] =
    pathSyntax(value, path, label)

  def symlinkTarget(
      value: String,
      path: String,
      installDir: String
  ): Vector[ValidationError] =
    val syntaxErrors = pathSyntax(value, path, "symlink target")
    if syntaxErrors.nonEmpty then syntaxErrors
    else
      Try:
        val installRoot = Path.of(installDir).toAbsolutePath.normalize()
        val rawTarget   = Path.of(value)
        val target      =
          if rawTarget.isAbsolute then rawTarget.toAbsolutePath.normalize()
          else installRoot.resolve(rawTarget).normalize()
        installRoot -> target
      match
        case Failure(error) =>
          Vector(ValidationError(path, s"invalid symlink target: ${Diagnostics.describe(error)}"))
        case Success((installRoot, target)) if !target.startsWith(installRoot) =>
          Vector(ValidationError(path, "symlink target must resolve inside installDir"))
        case Success(_) => Vector.empty

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
