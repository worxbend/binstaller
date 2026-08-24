package binstaller.core

import utest.*

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Duration

/** The JDK HTTP clients: redirect provenance, redirect refusal, and body limits. */
object HttpClientTest extends TestSuite with CoreTestSupport:

  val tests: Tests = Tests:
    test("JDK http-text client records direct no-redirect provenance"):
      val response = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/stable.txt",
        responseStatusCode = 200,
        responseBody = ByteArrayInputStream("v1.0.0".getBytes(StandardCharsets.UTF_8))
      )
      val client = JdkHttpTextClient(StaticHttpClient(response), _ => Right(()))

      val result = client.getTextWithProvenance("https://example.invalid/stable.txt")

      result match
        case Right(value) =>
          assert(value.text == "v1.0.0")
          assert(value.provenance.initialUrl == "https://example.invalid/stable.txt")
          assert(value.provenance.finalUrl == "https://example.invalid/stable.txt")
          assert(value.provenance.redirects.isEmpty)
        case Left(error) => abort(s"expected text response, got $error")

    test("JDK http-text client records multiple redirects"):
      val first = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/stable.txt",
        responseStatusCode = 302,
        responseBody = ByteArrayInputStream(Array.emptyByteArray)
      )
      val second = FakeHttpResponse[InputStream](
        responseUri = "https://cdn.example.invalid/releases/stable.txt",
        responseStatusCode = 301,
        responseBody = ByteArrayInputStream(Array.emptyByteArray),
        previous = Some(first)
      )
      val finalResponse = FakeHttpResponse[InputStream](
        responseUri = "https://mirror.example.invalid/releases/stable.txt",
        responseStatusCode = 200,
        responseBody = ByteArrayInputStream("v1.0.1".getBytes(StandardCharsets.UTF_8)),
        previous = Some(second)
      )
      val client = JdkHttpTextClient(StaticHttpClient(finalResponse), _ => Right(()))

      val result = client.getTextWithProvenance("https://example.invalid/stable.txt")

      result match
        case Right(value) =>
          assert(value.text == "v1.0.1")
          assert(value.provenance.initialUrl == "https://example.invalid/stable.txt")
          assert(value.provenance.finalUrl == "https://mirror.example.invalid/releases/stable.txt")
          assert(value.provenance.redirects.map(_.statusCode) == Vector(302, 301))
          assert(value.provenance.redirects.map(_.from) ==
            Vector(
              "https://example.invalid/stable.txt",
              "https://cdn.example.invalid/releases/stable.txt"
            ))
          assert(value.provenance.redirects.map(_.to) ==
            Vector(
              "https://cdn.example.invalid/releases/stable.txt",
              "https://mirror.example.invalid/releases/stable.txt"
            ))
        case Left(error) => abort(s"expected text response, got $error")

    test("HTTP clients reject local redirect targets before following them"):
      val redirect = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/stable.txt",
        responseStatusCode = 302,
        responseBody = ByteArrayInputStream(Array.emptyByteArray),
        responseHeaders = Map("Location" -> Vector("https://169.254.169.254/latest/meta-data"))
      )
      val client = JdkHttpTextClient(StaticHttpClient(redirect), _ => Right(()))

      val result = client.getTextWithProvenance("https://example.invalid/stable.txt")

      assert(result.left.exists(_.message.contains("unsafe redirect target")))

    test("JDK binary download client records direct no-redirect provenance"):
      val response = FakeHttpResponse[ByteArrayInputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = ByteArrayInputStream("alpha".getBytes(StandardCharsets.UTF_8)),
        responseHeaders = Map("Content-Length" -> Vector("5"))
      )
      val client = JdkBinaryDownloadClient(StaticHttpClient(response), hostGuard = _ => Right(()))

      val result = client.downloadWithProvenance("https://example.invalid/alpha")

      result match
        case Right(value) =>
          assert(String(value.bytes, StandardCharsets.UTF_8) == "alpha")
          assert(value.provenance == UrlProvenance.direct("https://example.invalid/alpha"))
        case Left(error) => abort(s"expected binary response, got $error")

    test("JDK binary download client records redirects and emits final URL progress"):
      val first = FakeHttpResponse[ByteArrayInputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 302,
        responseBody = ByteArrayInputStream(Array.emptyByteArray)
      )
      val finalResponse = FakeHttpResponse[ByteArrayInputStream](
        responseUri = "https://cdn.example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = ByteArrayInputStream("alpha".getBytes(StandardCharsets.UTF_8)),
        previous = Some(first),
        responseHeaders = Map("Content-Length" -> Vector("5"))
      )
      val progress = RecordingBinaryDownloadProgressObserver()
      val client   = JdkBinaryDownloadClient(StaticHttpClient(finalResponse), hostGuard = _ => Right(()))

      val result = client.downloadWithProvenance("https://example.invalid/alpha", progress)

      result match
        case Right(value) =>
          assert(String(value.bytes, StandardCharsets.UTF_8) == "alpha")
          assert(value.provenance.initialUrl == "https://example.invalid/alpha")
          assert(value.provenance.finalUrl == "https://cdn.example.invalid/alpha")
          assert(value.provenance.redirects ==
            Vector(UrlRedirectHop(
              "https://example.invalid/alpha",
              "https://cdn.example.invalid/alpha",
              302
            )))
          assert(progress.urls.distinct == Vector("https://cdn.example.invalid/alpha"))
        case Left(error) => abort(s"expected binary response, got $error")

    test("bounded body writer rejects oversized content length before buffering"):
      val result = BoundedBinaryBodyReader.write(
        "https://example.invalid/alpha",
        ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),
        ByteArrayOutputStream(),
        Some(11L),
        BinaryDownloadLimits(maxBytes = 10L, bodyTimeout = Duration.ofSeconds(5)),
        BinaryDownloadProgressObserver.none
      )

      assert(result.left.exists(_.message.contains("exceeds max allowed 10 bytes")))

    test("bounded body writer stops downloads that exceed max size without content length"):
      val result = BoundedBinaryBodyReader.write(
        "https://example.invalid/alpha",
        ByteArrayInputStream("oversized-body".getBytes(StandardCharsets.UTF_8)),
        ByteArrayOutputStream(),
        None,
        BinaryDownloadLimits(maxBytes = 4L, bodyTimeout = Duration.ofSeconds(5)),
        BinaryDownloadProgressObserver.none
      )

      assert(result.left.exists(_.message.contains("exceeds max allowed 4 bytes")))

    test("bounded body writer fails when body read exceeds deadline"):
      var now    = 0L
      val result = BoundedBinaryBodyReader.write(
        "https://example.invalid/alpha",
        ByteArrayInputStream("abcdef".getBytes(StandardCharsets.UTF_8)),
        ByteArrayOutputStream(),
        None,
        BinaryDownloadLimits(maxBytes = 1024L, bodyTimeout = Duration.ofNanos(1)),
        BinaryDownloadProgressObserver.none,
        nowNanos = () =>
          now = now + 2L
          now
      )

      assert(result.left.exists(_.message.contains("download body timed out")))
