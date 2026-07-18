package binstaller.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using

/** Expected failure from a binary download. */
final case class BinaryDownloadError(
    url: String,
    message: String,
    provenance: Option[UrlProvenance] = None
)

/** Downloaded artifact bytes paired with effective URL metadata. */
final case class BinaryDownloadResult(bytes: Array[Byte], provenance: UrlProvenance)

/** File-backed downloaded artifact used by the installation pipeline. */
final case class BinaryDownloadArtifact(
    path: Path,
    provenance: UrlProvenance,
    sha256: Sha256Digest,
    sizeBytes: Long
):
  def discard(): Unit =
    val _ = Try(Files.deleteIfExists(path))

/** Download progress events emitted by binary download clients. */
enum BinaryDownloadProgress:
  case Started(url: String, totalBytes: Option[Long])
  case Advanced(url: String, downloadedBytes: Long, totalBytes: Option[Long])
  case Finished(url: String, downloadedBytes: Long, totalBytes: Option[Long])

/** Observer for raw download progress events. */
trait BinaryDownloadProgressObserver:
  /** Receive one download progress event. */
  def onProgress(progress: BinaryDownloadProgress): Unit

/** Download progress observer constructors. */
object BinaryDownloadProgressObserver:
  /** Observer that ignores all progress events. */
  val none: BinaryDownloadProgressObserver = _ => ()

/** Boundary for fetching binary artifact bytes. */
trait BinaryDownloadClient:
  /** Download bytes without progress callbacks. */
  def download(url: String): Either[BinaryDownloadError, Array[Byte]]

  /** Download bytes and optionally emit progress callbacks. */
  def download(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, Array[Byte]] = progressObserver match
    case _ => download(url)

  /** Download bytes and report the initial URL, final URL, and redirect chain. */
  def downloadWithProvenance(url: String): Either[BinaryDownloadError, BinaryDownloadResult] =
    downloadWithProvenance(url, BinaryDownloadProgressObserver.none)

  /** Download bytes with progress callbacks and effective URL metadata. */
  def downloadWithProvenance(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, BinaryDownloadResult] = download(url, progressObserver).map(
    bytes => BinaryDownloadResult(bytes, UrlProvenance.direct(url))
  )

  /** Stream an artifact to an owned temporary file. Callers must discard it after staging. */
  def downloadArtifactWithProvenance(
      url: String,
      progressObserver: BinaryDownloadProgressObserver = BinaryDownloadProgressObserver.none
  ): Either[BinaryDownloadError, BinaryDownloadArtifact] = downloadWithProvenance(
    url,
    progressObserver
  ).flatMap: result =>
    Try:
      val path = Files.createTempFile("binstaller-download-", ".artifact")
      Files.write(path, result.bytes)
      BinaryDownloadArtifact(
        path,
        result.provenance,
        Sha256Digest.trusted(Sha256.digest(result.bytes)),
        result.bytes.length.toLong
      )
    match
      case Success(artifact) => Right(artifact)
      case Failure(error)    => Left(BinaryDownloadError(url, error.getMessage, Some(result.provenance)))

/** Binary download client constructors. */
object BinaryDownloadClient:
  /** JDK HTTP implementation with HTTPS, redirects, timeout, size, and body-time limits. */
  def jdk: BinaryDownloadClient = JdkBinaryDownloadClient(RuntimeHttpClient.create())

/** Runtime limits applied while reading downloaded artifact bodies. */
final case class BinaryDownloadLimits(maxBytes: Long, bodyTimeout: Duration)

/** Default binary-download limit values. */
object BinaryDownloadLimits:

  /** Conservative default sized for developer tools while bounding memory and stalled bodies. */
  val default: BinaryDownloadLimits = BinaryDownloadLimits(
    maxBytes = 512L * 1024L * 1024L,
    bodyTimeout = Duration.ofMinutes(30)
  )

private[core] final class JdkBinaryDownloadClient(
    client: HttpClient,
    limits: BinaryDownloadLimits = BinaryDownloadLimits.default
) extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] =
    download(url, BinaryDownloadProgressObserver.none)

  override def download(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, Array[Byte]] =
    downloadWithProvenance(url, progressObserver).map(_.bytes)

  override def downloadWithProvenance(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, BinaryDownloadResult] = downloadArtifactWithProvenance(
    url,
    progressObserver
  ).flatMap: artifact =>
    try Right(BinaryDownloadResult(Files.readAllBytes(artifact.path), artifact.provenance))
    catch case error: Exception => Left(BinaryDownloadError(url, error.getMessage, Some(artifact.provenance)))
    finally artifact.discard()

  override def downloadArtifactWithProvenance(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, BinaryDownloadArtifact] = RuntimeUrl.httpsUri(url) match
    case Left(message) => Left(BinaryDownloadError(url, message))
    case Right(uri)    =>
      val request = HttpRequest.newBuilder(uri).timeout(RuntimeHttpClient.requestTimeout).GET()
        .build()
      Try(client.send(request, HttpResponse.BodyHandlers.ofInputStream())) match
        case Success(response) if response.statusCode() >= 200 && response.statusCode() < 300 =>
          val provenance = UrlProvenance.fromResponse(url, response)
          RuntimeUrl.httpsUri(provenance.finalUrl) match
            case Left(message) => Left(BinaryDownloadError(url, message, Some(provenance)))
            case Right(_)      => readBodyToFile(provenance, response, progressObserver)
        case Success(response) =>
          val provenance = UrlProvenance.fromResponse(url, response)
          Left(BinaryDownloadError(url, s"HTTP ${response.statusCode()}", Some(provenance)))
        case Failure(error) => Left(BinaryDownloadError(url, error.getMessage))

  private def readBodyToFile(
      provenance: UrlProvenance,
      response: HttpResponse[InputStream],
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, BinaryDownloadArtifact] =
    val totalBytes = response.headers().firstValueAsLong("Content-Length") match
      case value if value.isPresent && value.getAsLong >= 0L => Some(value.getAsLong)
      case _                                                 => None

    val tempPath = Try(Files.createTempFile("binstaller-download-", ".artifact")) match
      case Failure(error) => return Left(BinaryDownloadError(
          provenance.initialUrl, error.getMessage, Some(provenance)
        ))
      case Success(path) => path
    (Try:
      Using.resource(response.body()): input =>
        val outputStream = Files.newOutputStream(
            tempPath,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
          )
        Using.resource(outputStream): output =>
          BoundedBinaryBodyReader.write(
            provenance.finalUrl,
            input,
            output,
            totalBytes,
            limits,
            progressObserver
          )
    ) match
      case Success(result) => result
          .map((digest, size) => BinaryDownloadArtifact(tempPath, provenance, digest, size))
          .left
          .map: error =>
            val _ = Files.deleteIfExists(tempPath)
            error.copy(url = provenance.initialUrl, provenance = Some(provenance))
      case Failure(error) =>
        val _ = Files.deleteIfExists(tempPath)
        Left(BinaryDownloadError(provenance.initialUrl, error.getMessage, Some(provenance)))

private[core] object BoundedBinaryBodyReader:

  def write(
      url: String,
      input: InputStream,
      output: OutputStream,
      totalBytes: Option[Long],
      limits: BinaryDownloadLimits,
      progressObserver: BinaryDownloadProgressObserver,
      nowNanos: () => Long = () => System.nanoTime()
  ): Either[BinaryDownloadError, (Sha256Digest, Long)] = totalBytes match
    case Some(length) if length > limits.maxBytes =>
      Left(BinaryDownloadError(url, maxSizeMessage(length, limits.maxBytes)))
    case _ => (Try:
        val deadline = nowNanos() + limits.bodyTimeout.toNanos
        val digest   = MessageDigest.getInstance("SHA-256")
        val buffer   = Array.ofDim[Byte](64 * 1024)
        var total    = 0L
        progressObserver.onProgress(BinaryDownloadProgress.Started(url, totalBytes))
        var count = input.read(buffer)
        while count != -1 do
          rejectAfterDeadline(nowNanos(), deadline, limits.bodyTimeout)
          total += count
          if total > limits.maxBytes then
            throw IllegalArgumentException(maxSizeMessage(total, limits.maxBytes))
          output.write(buffer, 0, count)
          digest.update(buffer, 0, count)
          progressObserver.onProgress(BinaryDownloadProgress.Advanced(url, total, totalBytes))
          count = input.read(buffer)
        rejectAfterDeadline(nowNanos(), deadline, limits.bodyTimeout)
        progressObserver.onProgress(BinaryDownloadProgress.Finished(url, total, totalBytes))
        Sha256Digest.trusted(digest.digest().map(byte => f"${byte & 0xff}%02x").mkString) -> total
      ) match
        case Success(result) => Right(result)
        case Failure(error)  => Left(BinaryDownloadError(url, error.getMessage))

  def read(
      url: String,
      input: InputStream,
      totalBytes: Option[Long],
      limits: BinaryDownloadLimits,
      progressObserver: BinaryDownloadProgressObserver,
      nowNanos: () => Long = () => System.nanoTime()
  ): Either[BinaryDownloadError, Array[Byte]] = totalBytes match
    // Reject oversized declared bodies before reading, then enforce the same limit while reading
    // because Content-Length can be absent or wrong.
    case Some(length) if length > limits.maxBytes =>
      Left(BinaryDownloadError(url, maxSizeMessage(length, limits.maxBytes)))
    case _ => readBounded(url, input, totalBytes, limits, progressObserver, nowNanos)

  private def readBounded(
      url: String,
      input: InputStream,
      totalBytes: Option[Long],
      limits: BinaryDownloadLimits,
      progressObserver: BinaryDownloadProgressObserver,
      nowNanos: () => Long
  ): Either[BinaryDownloadError, Array[Byte]] = Try:
    val deadline = nowNanos() + limits.bodyTimeout.toNanos
    progressObserver.onProgress(BinaryDownloadProgress.Started(url, totalBytes))
    val output = ByteArrayOutputStream()
    val buffer = Array.ofDim[Byte](64 * 1024)
    var read   = input.read(buffer)
    var total  = 0L

    while read != -1 do
      rejectAfterDeadline(nowNanos(), deadline, limits.bodyTimeout)
      total += read.toLong
      if total > limits.maxBytes then
        throw IllegalArgumentException(
          maxSizeMessage(total, limits.maxBytes)
        )
      output.write(buffer, 0, read)
      progressObserver.onProgress(BinaryDownloadProgress.Advanced(url, total, totalBytes))
      read = input.read(buffer)

    rejectAfterDeadline(nowNanos(), deadline, limits.bodyTimeout)
    progressObserver.onProgress(BinaryDownloadProgress.Finished(url, total, totalBytes))
    output.toByteArray
  match
    case Success(bytes) => Right(bytes)
    case Failure(error) => Left(BinaryDownloadError(url, error.getMessage))

  private def rejectAfterDeadline(now: Long, deadline: Long, timeout: Duration): Unit =
    if now > deadline then
      throw IllegalArgumentException(s"download body timed out after ${timeout.toSeconds}s")

  private def maxSizeMessage(actual: Long, maxBytes: Long): String =
    s"download size $actual exceeds max allowed $maxBytes bytes"
