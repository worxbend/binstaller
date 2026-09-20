package binstaller.core

import binstaller.config.Diagnostics

import java.net.URI
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Path
import scala.util.Try

/** Validated HTTPS URL used at the network boundary. */
final case class HttpsUrl private (value: String, uri: URI)

object HttpsUrl:

  def fromString(value: String): Either[String, HttpsUrl] = Try(URI.create(value)).toEither
    .left.map(error => s"invalid URL: ${Diagnostics.describe(error)}")
    .flatMap:
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
   * Resolve the host immediately before a request as fail-closed defense-in-depth. The
   * authoritative rebinding guarantee comes from the installed
   * [[GuardedInetAddressResolverProvider]]: the HTTP client re-resolves independently, so this
   * pre-check alone cannot pin the connected address.
   *
   * `resolve` is injectable so tests can drive all three fail-closed branches without a live DNS
   * lookup; production uses the JDK resolver.
   */
  def validateResolved(
      host: String,
      resolve: String => Array[InetAddress] = InetAddress.getAllByName
  ): Either[String, Unit] = Try(resolve(host).toVector).toEither.left
    // Naming the host and the cause is the difference between a report a user can act on and
    // one that collapses an unknown host, a refused resolver and a timeout into the same line.
    .map(error => s"URL host '$host' could not be resolved: ${Diagnostics.describe(error)}")
    .flatMap: addresses =>
      if addresses.isEmpty then Left(s"URL host '$host' did not resolve to any address")
      else if addresses.exists(isBlockedAddress) then
        Left(s"URL host '$host' resolves to a private, local, link-local, or multicast address")
      else Right(())

  private def isIpLiteral(host: String): Boolean = host.contains(':') ||
    host.nonEmpty && host.forall(character => character.isDigit || character == '.')

  /** Shared by the static literal path, the pre-request check, and the JVM-wide resolver guard. */
  private[core] def isBlockedAddress(address: InetAddress): Boolean = address.isAnyLocalAddress ||
    address.isLoopbackAddress || address.isLinkLocalAddress ||
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

/** A syntactically safe relative path that cannot traverse above its root. */
final case class RelativeInstallPath private (value: String, path: Path)

object RelativeInstallPath:

  def fromString(
      value: String,
      allowCurrentDirectory: Boolean = false
  ): Either[String, RelativeInstallPath] = PathSyntaxRules.validate(value) match
    case Left(violation) => Left(violation.message)
    case Right(())       => Try(Path.of(value)).toEither
        .left.map(error => s"is invalid: ${Diagnostics.describe(error)}")
        .flatMap:
          case path if path.isAbsolute                                => Left("must be relative")
          case path if path.toString == "." && !allowCurrentDirectory =>
            Left("must not be current directory")
          case path => Right(RelativeInstallPath(value, path.normalize()))
