package binstaller.core

import utest.*

import java.net.InetAddress
import scala.jdk.CollectionConverters.*

/** URL and DNS-target guards: what counts as a safe host to connect to. */
object NetworkGuardTest extends TestSuite with CoreTestSupport:

  val tests: Tests = Tests:
    test("https url validation accepts uppercase scheme and public hosts"):
      assert(HttpsUrl.fromString("HTTPS://example.com/tool.tar.gz").isRight)
      assert(HttpsUrl.fromString("https://example.com/tool.tar.gz").isRight)

    test("https url validation rejects non-https and userinfo-bearing urls"):
      assert(HttpsUrl.fromString("http://example.com/tool").isLeft)
      assert(HttpsUrl.fromString("https://user:pass@example.com/tool").isLeft)

    test("network target guard rejects loopback link-local and metadata hosts"):
      val blocked = Vector(
        "localhost",
        "localhost.",
        "sub.localhost",
        "service.local",
        "127.0.0.1",
        "0.0.0.0",
        "0.1.2.3",
        "100.64.0.1",
        "100.127.255.1",
        "169.254.169.254",
        "10.0.0.5",
        "192.168.1.10",
        "metadata.google.internal",
        "[::1]",
        "[fc00::1]",
        "[fd12:3456:789a::1]"
      )
      blocked.foreach: host =>
        assert(NetworkTargetGuard.validate(host).isLeft)

    test("network target guard allows ordinary public hosts and ip literals"):
      assert(NetworkTargetGuard.validate("example.com").isRight)
      assert(NetworkTargetGuard.validate("8.8.8.8").isRight)
      assert(NetworkTargetGuard.validate("101.64.0.1").isRight)

    test("validateResolved fails closed and names the host on every branch"):
      // The resolver is injected rather than looked up for real: a live lookup of an .invalid host
      // is slow behind a long-timeout resolver and outright wrong behind a captive portal, which
      // answers every name and would turn this into a false pass.
      val threw = NetworkTargetGuard.validateResolved(
        "unknown.example",
        _ => throw java.net.UnknownHostException("no such host")
      )
      assert(threw.left.exists(message =>
        message.contains("unknown.example") && message.contains("no such host")
      ))

      val empty = NetworkTargetGuard.validateResolved("empty.example", _ => Array.empty)
      assert(empty.left.exists(_.contains("did not resolve to any address")))

      val private_ = NetworkTargetGuard.validateResolved(
        "rebind.example",
        _ => Array(InetAddress.getByName("10.0.0.5"))
      )
      assert(private_.left.exists(_.contains("private, local, link-local, or multicast")))

    test("guarded resolver drops blocked addresses and fails closed when none remain"):
      val privateAddr = InetAddress.getByName("10.0.0.5")
      val publicAddr  = InetAddress.getByName("8.8.8.8")
      val filtered = GuardedInetAddressResolverProvider
        .guard("mixed.example", java.util.stream.Stream.of(publicAddr, privateAddr))
        .iterator()
        .asScala
        .toVector
      assert(filtered == Vector(publicAddr))
      val rebindOnly = scala.util.Try(
        GuardedInetAddressResolverProvider
          .guard("rebind.example", java.util.stream.Stream.of(privateAddr))
          .iterator()
          .asScala
          .toVector
      )
      assert(rebindOnly.failed.toOption.exists(_.isInstanceOf[java.net.UnknownHostException]))
