package binstaller.core

import java.net.URI
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Validated HTTPS URL used at the network boundary. */
final case class HttpsUrl private (value: String, uri: URI)

object HttpsUrl:

  def fromString(value: String): Either[String, HttpsUrl] =
    Try(URI.create(value)).toEither.left.map(error => s"invalid URL: ${error.getMessage}").flatMap:
      case uri if !Option(uri.getScheme).exists(_.equalsIgnoreCase("https")) =>
        Left("URL must use https")
      case uri if Option(uri.getHost).forall(_.isEmpty) => Left("URL must include a host")
      case uri if uri.getUserInfo != null => Left("URL must not include user information")
      case uri => NetworkTargetGuard.validate(uri.getHost).map(_ => HttpsUrl(value, uri))

private[core] object NetworkTargetGuard:

  private val blockedNames = Set(
    "localhost",
    "localhost.localdomain",
    "metadata.google.internal"
  )

  def validate(host: String): Either[String, Unit] =
    // A single trailing dot (FQDN root) still resolves to the same target, so strip it before the
    // static name/literal checks or `localhost.` would slip past them.
    val normalized = host.stripPrefix("[").stripSuffix("]").stripSuffix(".").toLowerCase
    if blockedNames(normalized) || normalized.endsWith(".localhost") ||
      normalized.endsWith(".local")
    then Left("URL host must not target a local or metadata endpoint")
    else if isIpLiteral(normalized) then
      Try(InetAddress.getByName(normalized)).toEither.left
        .map(_ => "URL host contains an invalid IP address")
        .flatMap: address =>
          // Route the literal through the same predicate as resolved addresses so the static and
          // resolved checks cannot drift.
          if isBlockedAddress(address) then
            Left("URL host must not target a private, local, link-local, or multicast address")
          else Right(())
    else Right(())

  /**
   * Resolve the host immediately before a request as fail-closed defense-in-depth. The authoritative
   * rebinding guarantee comes from the installed [[GuardedInetAddressResolverProvider]]: the HTTP
   * client re-resolves independently, so this pre-check alone cannot pin the connected address.
   */
  def validateResolved(host: String): Either[String, Unit] =
    Try(InetAddress.getAllByName(host).toVector).toEither.left
      .map(_ => "URL host could not be resolved")
      .flatMap: addresses =>
        if addresses.isEmpty then Left("URL host did not resolve to any address")
        else if addresses.exists(isBlockedAddress) then
          Left("URL host resolves to a private, local, link-local, or multicast address")
        else Right(())

  private def isIpLiteral(host: String): Boolean = host.contains(':') ||
    host.nonEmpty && host.forall(character => character.isDigit || character == '.')

  /** Shared by the static literal path, the pre-request check, and the JVM-wide resolver guard. */
  private[core] def isBlockedAddress(address: InetAddress): Boolean =
    address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
      address.isSiteLocalAddress || address.isMulticastAddress ||
      isUniqueLocalIpv6(address) || isCarrierGradeNat(address) || isUnspecifiedIpv4Block(address)

  // IPv6 unique-local fc00::/7 (first byte 1111 110x). InetAddress unmaps ::ffff:v4 to Inet4Address,
  // so IPv4-mapped forms fall through to the IPv4 predicates above.
  private def isUniqueLocalIpv6(address: InetAddress): Boolean = address match
    case v6: Inet6Address => (v6.getAddress()(0) & 0xfe) == 0xfc
    case _                => false

  // IPv4 carrier-grade NAT 100.64.0.0/10.
  private def isCarrierGradeNat(address: InetAddress): Boolean = address match
    case v4: Inet4Address =>
      val bytes = v4.getAddress()
      (bytes(0) & 0xff) == 100 && (bytes(1) & 0xc0) == 0x40
    case _ => false

  // IPv4 "this network" 0.0.0.0/8.
  private def isUnspecifiedIpv4Block(address: InetAddress): Boolean = address match
    case v4: Inet4Address => (v4.getAddress()(0) & 0xff) == 0
    case _                => false

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
        case path if path.isAbsolute                                    => Left("must be relative")
        case path if path.iterator().asScala.exists(_.toString == "..") =>
          Left("must not contain traversal segments")
        case path if path.toString == "." && !allowCurrentDirectory =>
          Left("must not be current directory")
        case path => Right(RelativeInstallPath(value, path.normalize()))
