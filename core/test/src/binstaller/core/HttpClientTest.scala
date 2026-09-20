package binstaller.core

import utest.*

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import ox.forkCancellable
import ox.unsupervised

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

    test("HTTP clients stop following redirects once the budget is exhausted"):
      val body     = CloseTrackingInputStream(Array.emptyByteArray)
      val redirect = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/stable.txt",
        responseStatusCode = 302,
        responseBody = body,
        responseHeaders = Map("Location" -> Vector("https://example.invalid/next.txt"))
      )
      val client = JdkHttpTextClient(StaticHttpClient(redirect), _ => Right(()))

      val result = client.getTextWithProvenance("https://example.invalid/stable.txt")

      assert(result.left.exists(_.message.contains("HTTP redirect limit exceeded (10)")))
      assert(body.isClosed)

    test("HTTP clients fail a redirect that carries no Location header"):
      val body     = CloseTrackingInputStream(Array.emptyByteArray)
      val redirect = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/stable.txt",
        responseStatusCode = 302,
        responseBody = body
      )
      val client = JdkHttpTextClient(StaticHttpClient(redirect), _ => Right(()))

      val result = client.getTextWithProvenance("https://example.invalid/stable.txt")

      assert(result.left.exists(_.message.contains("HTTP 302 redirect is missing Location")))
      assert(body.isClosed)

    test("HTTP clients fail a redirect whose Location header is blank"):
      val body     = CloseTrackingInputStream(Array.emptyByteArray)
      val redirect = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/stable.txt",
        responseStatusCode = 307,
        responseBody = body,
        responseHeaders = Map("Location" -> Vector("   "))
      )
      val client = JdkHttpTextClient(StaticHttpClient(redirect), _ => Right(()))

      val result = client.getTextWithProvenance("https://example.invalid/stable.txt")

      assert(result.left.exists(_.message.contains("HTTP 307 redirect is missing Location")))
      assert(body.isClosed)

    test("HTTP clients fail a redirect whose Location header is malformed"):
      val body     = CloseTrackingInputStream(Array.emptyByteArray)
      val redirect = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/stable.txt",
        responseStatusCode = 301,
        responseBody = body,
        responseHeaders = Map("Location" -> Vector("https://exa mple.invalid/next.txt"))
      )
      val client = JdkHttpTextClient(StaticHttpClient(redirect), _ => Right(()))

      val result = client.getTextWithProvenance("https://example.invalid/stable.txt")

      assert(result.left.exists(_.message.contains("invalid redirect Location")))
      assert(body.isClosed)

    test("HTTP clients fail closed on a rejecting host guard before issuing any request"):
      val transport = StaticHttpClient(FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/stable.txt",
        responseStatusCode = 200,
        responseBody = ByteArrayInputStream("v1.0.0".getBytes(StandardCharsets.UTF_8))
      ))
      val guard: String => Either[String, Unit] = _ => Left("host guard rejected example.invalid")
      val text                                  = JdkHttpTextClient(transport, guard)
      val binary   = JdkBinaryDownloadClient(transport, hostGuard = guard)
      val metadata = JdkBinaryMetadataClient(transport, hostGuard = guard)

      assert(text.getTextWithProvenance("https://example.invalid/stable.txt")
        .left.exists(_.message.contains("host guard rejected example.invalid")))
      assert(binary.downloadWithProvenance("https://example.invalid/alpha")
        .left.exists(_.message.contains("host guard rejected example.invalid")))
      assert(metadata.metadata("https://example.invalid/alpha")
        .left.exists(_.message.contains("host guard rejected example.invalid")))
      assert(transport.requestCount == 0)

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
      val client   =
        JdkBinaryDownloadClient(StaticHttpClient(finalResponse), hostGuard = _ => Right(()))

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

    test("binary client times out a stalled body and closes it"):
      val temporaryPath = tempDirectory("core-http-timeout").resolve("artifact")
      val body          = InterruptibleStalledInputStream()
      val response      = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = body
      )
      val client = JdkBinaryDownloadClient(
        StaticHttpClient(response),
        limits = BinaryDownloadLimits(1024L, Duration.ofMillis(50)),
        hostGuard = _ => Right(()),
        createTemporaryFile = () => Files.createFile(temporaryPath)
      )

      val startedAt = System.nanoTime()
      val result    = client.downloadArtifactWithProvenance("https://example.invalid/alpha")

      assert(result.left.exists(_.message.contains("download body timed out")))
      assert(body.isClosed)
      assert(!Files.exists(temporaryPath))
      assert(System.nanoTime() - startedAt < Duration.ofSeconds(2).toNanos)

    test("text client times out a stalled body and closes it"):
      val body     = InterruptibleStalledInputStream()
      val response = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/stable.txt",
        responseStatusCode = 200,
        responseBody = body
      )
      val client = JdkHttpTextClient(
        StaticHttpClient(response),
        hostGuard = _ => Right(()),
        bodyTimeout = Duration.ofMillis(50)
      )

      val startedAt = System.nanoTime()
      val result    = client.getText("https://example.invalid/stable.txt")

      assert(result.left.exists(_.message.contains("body timed out")))
      assert(body.isClosed)
      assert(System.nanoTime() - startedAt < Duration.ofSeconds(2).toNanos)

    test("metadata client times out a stalled body and closes it"):
      val body     = InterruptibleStalledInputStream()
      val response = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = body
      )
      val client = JdkBinaryMetadataClient(
        StaticHttpClient(response),
        hostGuard = _ => Right(()),
        bodyTimeout = Duration.ofMillis(50)
      )

      val startedAt = System.nanoTime()
      val result    = client.metadata("https://example.invalid/alpha")

      assert(result.left.exists(_.message.contains("body timed out")))
      assert(body.isClosed)
      assert(System.nanoTime() - startedAt < Duration.ofSeconds(2).toNanos)

    test("cancelling a binary body read closes it and discards the temporary file"):
      val temporaryPath = tempDirectory("core-http-cancel").resolve("artifact")
      val body          = InterruptibleStalledInputStream()
      val response      = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = body
      )
      val client = JdkBinaryDownloadClient(
        StaticHttpClient(response),
        limits = BinaryDownloadLimits(1024L, Duration.ofSeconds(30)),
        hostGuard = _ => Right(()),
        createTemporaryFile = () => Files.createFile(temporaryPath)
      )

      val cancelled = unsupervised:
        val running = forkCancellable(
          client.downloadArtifactWithProvenance("https://example.invalid/alpha")
        )
        assert(body.awaitRead())
        running.cancel()

      assert(cancelled.left.exists(_.isInstanceOf[InterruptedException]))
      assert(body.isClosed)
      assert(!Files.exists(temporaryPath))

    test("response close failures remain typed and discard a completed binary artifact"):
      val temporaryPath = tempDirectory("core-http-close").resolve("artifact")
      def response      = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = new ByteArrayInputStream("alpha".getBytes(StandardCharsets.UTF_8)):
          override def close(): Unit = throw java.io.IOException("response close failed")
      )
      val binary = JdkBinaryDownloadClient(
        StaticHttpClient(response),
        hostGuard = _ => Right(()),
        createTemporaryFile = () => Files.createFile(temporaryPath)
      )
      val text     = JdkHttpTextClient(StaticHttpClient(response), hostGuard = _ => Right(()))
      val metadata = JdkBinaryMetadataClient(StaticHttpClient(response), hostGuard = _ => Right(()))

      assert(binary.downloadArtifactWithProvenance("https://example.invalid/alpha")
        .left.exists(_.message.contains("response close failed")))
      assert(text.getText("https://example.invalid/alpha")
        .left.exists(_.message.contains("response close failed")))
      assert(metadata.metadata("https://example.invalid/alpha")
        .left.exists(_.message.contains("response close failed")))
      assert(!Files.exists(temporaryPath))

    test("HTTP failures retain their status when closing a rejected response fails"):
      def response = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 503,
        responseBody = new ByteArrayInputStream(Array.emptyByteArray):
          override def close(): Unit = throw java.io.IOException("response close failed")
      )
      val binary   = JdkBinaryDownloadClient(StaticHttpClient(response), hostGuard = _ => Right(()))
      val text     = JdkHttpTextClient(StaticHttpClient(response), hostGuard = _ => Right(()))
      val metadata = JdkBinaryMetadataClient(StaticHttpClient(response), hostGuard = _ => Right(()))

      val failures = Vector(
        binary.downloadArtifactWithProvenance("https://example.invalid/alpha")
          .left.toOption.map(error => error.message -> error.provenance),
        text.getText("https://example.invalid/alpha")
          .left.toOption.map(error => error.message -> error.provenance),
        metadata.metadata("https://example.invalid/alpha")
          .left.toOption.map(error => error.message -> error.provenance)
      )
      assert(failures.forall(_.contains(
        "HTTP 503" -> Some(UrlProvenance.direct("https://example.invalid/alpha"))
      )))

    test("binary client closes the response body when temporary allocation fails"):
      val body     = CloseTrackingInputStream("alpha".getBytes(StandardCharsets.UTF_8))
      val response = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = body
      )
      val client = JdkBinaryDownloadClient(
        StaticHttpClient(response),
        hostGuard = _ => Right(()),
        createTemporaryFile = () => throw java.io.IOException("temporary allocation failed")
      )

      val result = client.downloadArtifactWithProvenance("https://example.invalid/alpha")

      assert(result.left.exists(_.message.contains("temporary allocation failed")))
      assert(body.isClosed)

private final class InterruptibleStalledInputStream extends InputStream:
  private val closed  = AtomicBoolean(false)
  private val reading = CountDownLatch(1)

  def isClosed: Boolean    = closed.get()
  def awaitRead(): Boolean = reading.await(2, TimeUnit.SECONDS)

  override def read(): Int =
    reading.countDown()
    Thread.sleep(30_000L)
    -1

  override def close(): Unit = closed.set(true)

private final class CloseTrackingInputStream(bytes: Array[Byte])
    extends ByteArrayInputStream(bytes):
  private val closed = AtomicBoolean(false)

  def isClosed: Boolean = closed.get()

  override def close(): Unit =
    closed.set(true)
    super.close()
