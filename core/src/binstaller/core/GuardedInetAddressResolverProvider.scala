package binstaller.core

import java.net.InetAddress
import java.net.UnknownHostException
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolverProvider
import java.util.stream.Stream
import scala.jdk.CollectionConverters.*

/**
 * JVM-wide DNS guard. Every name resolution performed by the process — including the one the HTTP
 * client makes on its own connect path — is filtered so a hostname can only resolve to public
 * addresses. This is what actually closes the DNS-rebinding window: [[NetworkTargetGuard.validate]]
 * and `validateResolved` are pre-checks the client re-resolves past, but the client cannot connect
 * to any address this resolver refuses to return.
 *
 * Registered via `META-INF/services/java.net.spi.InetAddressResolverProvider` in the app module so
 * it is active only in the shipped binary, never during library tests (a process-global DNS filter
 * would break any test that resolves a loopback address). The filtering logic is unit-tested
 * directly through [[GuardedInetAddressResolverProvider.guard]].
 */
final class GuardedInetAddressResolverProvider extends InetAddressResolverProvider:

  def name(): String = "binstaller-network-target-guard"

  def get(configuration: InetAddressResolverProvider.Configuration): InetAddressResolver =
    val builtin = configuration.builtinResolver()
    new InetAddressResolver:
      def lookupByName(
          host: String,
          lookupPolicy: InetAddressResolver.LookupPolicy
      ): Stream[InetAddress] =
        GuardedInetAddressResolverProvider.guard(
          host,
          builtin.lookupByName(host, lookupPolicy)
        )

      def lookupByAddress(address: Array[Byte]): String = builtin.lookupByAddress(address)

object GuardedInetAddressResolverProvider:

  /**
   * Drop any blocked address the delegate returned and fail closed when nothing public remains, so
   * a rebinding answer of `[public, private]` still cannot expose the private target. Returning a
   * filtered subset (rather than throwing when *any* address is blocked) keeps legitimate split
   * horizon hosts resolvable while guaranteeing the client only ever sees vetted addresses.
   */
  private[core] def guard(host: String, resolved: Stream[InetAddress]): Stream[InetAddress] =
    val vetted = resolved.iterator().asScala.filterNot(NetworkTargetGuard.isBlockedAddress).toVector
    if vetted.isEmpty then
      throw UnknownHostException(
        s"$host resolves only to blocked (private, local, link-local, or multicast) addresses"
      )
    vetted.asJava.stream()
