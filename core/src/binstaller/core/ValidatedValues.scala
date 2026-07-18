package binstaller.core

import java.net.URI
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Validated HTTPS URL used at the network boundary. */
final case class HttpsUrl private (value: String, uri: URI)

object HttpsUrl:
  def fromString(value: String): Either[String, HttpsUrl] =
    Try(URI.create(value)).toEither.left.map(error => s"invalid URL: ${error.getMessage}").flatMap:
      case uri if uri.getScheme != "https" => Left("URL must use https")
      case uri if Option(uri.getHost).forall(_.isEmpty) => Left("URL must include a host")
      case uri if uri.getUserInfo != null => Left("URL must not include user information")
      case uri => Right(HttpsUrl(value, uri))

/** Normalized absolute installation root. */
final case class InstallRoot private (path: Path)

object InstallRoot:
  def fromString(value: String): Either[String, InstallRoot] =
    if value.trim.isEmpty then Left("install root must not be empty")
    else
      Try(Path.of(value).toAbsolutePath.normalize()).toEither
        .left.map(error => s"invalid install root: ${error.getMessage}")
        .map(InstallRoot(_))

/** A syntactically safe relative path that cannot traverse above its root. */
final case class RelativeInstallPath private (value: String, path: Path)

object RelativeInstallPath:
  def fromString(
      value: String,
      allowCurrentDirectory: Boolean = false
  ): Either[String, RelativeInstallPath] =
    if value.trim.isEmpty then Left("must not be empty")
    else if value.exists(Character.isISOControl) then Left("must not contain control characters")
    else if value.contains('\\') then Left("must not contain backslashes")
    else if value.matches("^[A-Za-z]:.*") then Left("must not be drive-prefixed")
    else
      Try(Path.of(value)).toEither.left.map(error => s"is invalid: ${error.getMessage}").flatMap:
        case path if path.isAbsolute => Left("must be relative")
        case path if path.iterator().asScala.exists(_.toString == "..") =>
          Left("must not contain traversal segments")
        case path if path.toString == "." && !allowCurrentDirectory =>
          Left("must not be current directory")
        case path => Right(RelativeInstallPath(value, path.normalize()))
