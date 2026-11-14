package binstaller.core

import binstaller.config.Sha256Digest
import utest.*

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import ox.forkCancellable
import ox.unsupervised
import scala.jdk.CollectionConverters.*

/** The JDK HTTP clients: redirect provenance, redirect refusal, body limits, and artifact files. */
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
        responseBody = ByteArrayInputStream(Array.emptyByteArray),
        responseHeaders =
          Map("Location" -> Vector("https://cdn.example.invalid/releases/stable.txt"))
      )
      val second = FakeHttpResponse[InputStream](
        responseUri = "https://cdn.example.invalid/releases/stable.txt",
        responseStatusCode = 301,
        responseBody = ByteArrayInputStream(Array.emptyByteArray),
        responseHeaders =
          Map("Location" -> Vector("https://mirror.example.invalid/releases/stable.txt"))
      )
      val finalResponse = FakeHttpResponse[InputStream](
        responseUri = "https://mirror.example.invalid/releases/stable.txt",
        responseStatusCode = 200,
        responseBody = ByteArrayInputStream("v1.0.1".getBytes(StandardCharsets.UTF_8))
      )
      val client =
        JdkHttpTextClient(RoutingHttpClient(first, second, finalResponse), _ => Right(()))

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

    test("HTTP clients follow a redirect, guarding each hop host and closing the hop body"):
      val hopBody   = CloseTrackingInputStream(Array.emptyByteArray)
      val transport = RoutingHttpClient(
        FakeHttpResponse[InputStream](
          responseUri = "https://example.invalid/stable.txt",
          responseStatusCode = 302,
          responseBody = hopBody,
          responseHeaders = Map("Location" -> Vector("https://cdn.example.invalid/stable.txt"))
        ),
        FakeHttpResponse[InputStream](
          responseUri = "https://cdn.example.invalid/stable.txt",
          responseStatusCode = 200,
          responseBody = ByteArrayInputStream("v1.0.1".getBytes(StandardCharsets.UTF_8))
        )
      )
      var guardedHosts = Vector.empty[String]
      val client       = JdkHttpTextClient(
        transport,
        host =>
          guardedHosts = guardedHosts :+ host
          Right(())
      )

      val result = client.getTextWithProvenance("https://example.invalid/stable.txt")

      assert(result ==
        Right(HttpTextResponse(
          "v1.0.1",
          UrlProvenance(
            "https://example.invalid/stable.txt",
            "https://cdn.example.invalid/stable.txt",
            Vector(UrlRedirectHop(
              "https://example.invalid/stable.txt",
              "https://cdn.example.invalid/stable.txt",
              302
            ))
          )
        )))
      assert(guardedHosts == Vector("example.invalid", "cdn.example.invalid"))
      assert(transport.requestedUrls ==
        Vector("https://example.invalid/stable.txt", "https://cdn.example.invalid/stable.txt"))
      assert(hopBody.isClosed)

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
      val binary                                = JdkBinaryDownloadClient(
        transport,
        hostGuard = guard,
        artifactFiles = ScopedArtifacts("core-http-guard").files
      )
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
      val client = JdkBinaryDownloadClient(
        StaticHttpClient(response),
        hostGuard = _ => Right(()),
        artifactFiles = ScopedArtifacts("core-http-direct").files
      )

      val result = client.downloadWithProvenance("https://example.invalid/alpha")

      result match
        case Right(value) =>
          assert(String(value.bytes, StandardCharsets.UTF_8) == "alpha")
          assert(value.provenance == UrlProvenance.direct("https://example.invalid/alpha"))
        case Left(error) => abort(s"expected binary response, got $error")

    test("JDK binary download client records redirects and emits final URL progress"):
      val first = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 302,
        responseBody = ByteArrayInputStream(Array.emptyByteArray),
        responseHeaders = Map("Location" -> Vector("https://cdn.example.invalid/alpha"))
      )
      val finalResponse = FakeHttpResponse[InputStream](
        responseUri = "https://cdn.example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = ByteArrayInputStream("alpha".getBytes(StandardCharsets.UTF_8)),
        responseHeaders = Map("Content-Length" -> Vector("5"))
      )
      val progress = RecordingBinaryDownloadProgressObserver()
      val client   = JdkBinaryDownloadClient(
        RoutingHttpClient(first, finalResponse),
        hostGuard = _ => Right(()),
        artifactFiles = ScopedArtifacts("core-http-redirect").files
      )

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

    test("lock inspection and install download report the same digest and size"):
      val bytes = "alpha".getBytes(StandardCharsets.UTF_8)

      val inspected = Sha256.digestStream(ByteArrayInputStream(bytes), maxBytes = 5L)
      val installed = boundedWrite(bytes, maxBytes = 5L)

      assert(inspected.map((digest, size) => digest.value -> size) ==
        Right(Sha256.digest(bytes) -> 5L))
      assert(installed == inspected)

    test("lock inspection and install download report an oversized body with the same message"):
      val bytes = "alpha".getBytes(StandardCharsets.UTF_8)

      val inspected = Sha256.digestStream(ByteArrayInputStream(bytes), maxBytes = 4L)
      val installed = boundedWrite(bytes, maxBytes = 4L)

      assert(inspected == Left("download size 5 exceeds max allowed 4 bytes"))
      assert(installed == inspected)

    test("binary client times out a stalled body and closes it"):
      val artifacts = ScopedArtifacts("core-http-timeout")
      val body      = InterruptibleStalledInputStream()
      val response  = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = body
      )
      val client = JdkBinaryDownloadClient(
        StaticHttpClient(response),
        limits = BinaryDownloadLimits(1024L, Duration.ofMillis(50)),
        hostGuard = _ => Right(()),
        artifactFiles = artifacts.files
      )

      val startedAt = System.nanoTime()
      val result    = client.downloadArtifactWithProvenance("https://example.invalid/alpha")

      assert(result.left.exists(_.message.contains("download body timed out")))
      assert(body.isClosed)
      assert(artifacts.allDiscarded)
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
      val artifacts = ScopedArtifacts("core-http-cancel")
      val body      = InterruptibleStalledInputStream()
      val response  = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = body
      )
      val client = JdkBinaryDownloadClient(
        StaticHttpClient(response),
        limits = BinaryDownloadLimits(1024L, Duration.ofSeconds(30)),
        hostGuard = _ => Right(()),
        artifactFiles = artifacts.files
      )

      val cancelled = unsupervised:
        val running = forkCancellable(
          client.downloadArtifactWithProvenance("https://example.invalid/alpha")
        )
        assert(body.awaitRead())
        running.cancel()

      assert(cancelled.left.exists(_.isInstanceOf[InterruptedException]))
      assert(body.isClosed)
      assert(artifacts.allDiscarded)

    test("response close failures remain typed and discard a completed binary artifact"):
      val artifacts = ScopedArtifacts("core-http-close")
      def response  = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = new ByteArrayInputStream("alpha".getBytes(StandardCharsets.UTF_8)):
          override def close(): Unit = throw java.io.IOException("response close failed")
      )
      val binary = JdkBinaryDownloadClient(
        StaticHttpClient(response),
        hostGuard = _ => Right(()),
        artifactFiles = artifacts.files
      )
      val text     = JdkHttpTextClient(StaticHttpClient(response), hostGuard = _ => Right(()))
      val metadata = JdkBinaryMetadataClient(StaticHttpClient(response), hostGuard = _ => Right(()))

      assert(binary.downloadArtifactWithProvenance("https://example.invalid/alpha")
        .left.exists(_.message.contains("response close failed")))
      assert(text.getText("https://example.invalid/alpha")
        .left.exists(_.message.contains("response close failed")))
      assert(metadata.metadata("https://example.invalid/alpha")
        .left.exists(_.message.contains("response close failed")))
      assert(artifacts.allDiscarded)

    test("HTTP failures retain their status when closing a rejected response fails"):
      def response = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 503,
        responseBody = new ByteArrayInputStream(Array.emptyByteArray):
          override def close(): Unit = throw java.io.IOException("response close failed")
      )
      val binary = JdkBinaryDownloadClient(
        StaticHttpClient(response),
        hostGuard = _ => Right(()),
        artifactFiles = ScopedArtifacts("core-http-status").files
      )
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
      val missingDirectory = tempDirectory("core-http-allocation").resolve("missing")
      val client           = JdkBinaryDownloadClient(
        StaticHttpClient(response),
        hostGuard = _ => Right(()),
        artifactFiles = DownloadArtifactFiles(missingDirectory, _ => (), () => Instant.now())
      )

      val result = client.downloadArtifactWithProvenance("https://example.invalid/alpha")

      assert(result.left.exists(_.message.contains(missingDirectory.toString)))
      assert(body.isClosed)

    test("download artifact files reclaim stale artifacts and keep young or foreign files"):
      val directory = tempDirectory("core-download-sweep")
      val now       = Instant.parse("2026-01-01T12:00:00Z")

      def aged(name: String, age: Duration): Path = Files.setLastModifiedTime(
        Files.createFile(directory.resolve(name)),
        FileTime.from(now.minus(age))
      )

      val stale   = aged("binstaller-download-stale.artifact", Duration.ofHours(2))
      val young   = aged("binstaller-download-young.artifact", Duration.ofMinutes(59))
      val foreign = aged("another-tool-download.artifact", Duration.ofHours(2))
      val files   = DownloadArtifactFiles(directory, _ => (), () => now)

      val created = files.create()

      assert(!Files.exists(stale))
      assert(Files.exists(young))
      assert(Files.exists(foreign))
      assert(Files.exists(created))

    test("the default download client sweeps and exit-deletes artifacts in the system temp dir"):
      val client = JdkBinaryDownloadClient(StaticHttpClient(FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = ByteArrayInputStream(Array.emptyByteArray)
      )))

      assert(client.artifactFiles.directory == Path.of(System.getProperty("java.io.tmpdir")))
      assert(client.artifactFiles.deleteAtExit eq DownloadArtifactFiles.exitDeletion)

    test("download artifact files register each new artifact for deletion at exit"):
      val directory  = tempDirectory("core-download-exit")
      var registered = Vector.empty[Path]
      val files      = DownloadArtifactFiles(
        directory,
        artifact => registered = registered :+ artifact,
        () => Instant.now()
      )

      val created = files.create()

      assert(registered == Vector(created))
      assert(created.getParent == directory)

  /** Artifact files in a directory of this test's own, recording each artifact created there. */
  private final class ScopedArtifacts(name: String):
    private val created = ConcurrentLinkedQueue[Path]()

    val files: DownloadArtifactFiles =
      DownloadArtifactFiles(tempDirectory(name), record, () => Instant.now())

    private def record(artifact: Path): Unit =
      val _ = created.add(artifact)

    /** At least one artifact was created, and none is left. */
    def allDiscarded: Boolean = !created.isEmpty && created.asScala.forall(!Files.exists(_))

  private def boundedWrite(
      bytes: Array[Byte],
      maxBytes: Long
  ): Either[String, (Sha256Digest, Long)] = BoundedBinaryBodyReader.write(
    "https://example.invalid/alpha",
    ByteArrayInputStream(bytes),
    ByteArrayOutputStream(),
    None,
    BinaryDownloadLimits(maxBytes, bodyTimeout = Duration.ofSeconds(5)),
    BinaryDownloadProgressObserver.none
  ).left.map(_.message)

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
