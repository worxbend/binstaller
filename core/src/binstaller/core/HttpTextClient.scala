package binstaller.core

import binstaller.config.Diagnostics

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.net.http.HttpClient
import java.time.Duration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Expected failure from a text version resolver. */
final case class HttpTextError(
    url: String,
    message: String,
    provenance: Option[UrlProvenance] = None
)

/** Text response paired with the effective URL metadata observed by the HTTP client. */
final case class HttpTextResponse(text: String, provenance: UrlProvenance)

/** Boundary for fetching small text values such as version resolver endpoints. */
trait HttpTextClient:
  /** Fetch text from a URL, returning domain errors rather than throwing expected failures. */
  def getText(url: String): Either[HttpTextError, String] = getTextWithProvenance(url).map(_.text)

  /** Fetch text and report the initial URL, final URL, and redirect chain. */
  def getTextWithProvenance(url: String): Either[HttpTextError, HttpTextResponse]

/** HTTP text client constructors. */
object HttpTextClient:
  /** JDK HTTP implementation with HTTPS, timeout, and normal redirect handling. */
  def jdk: HttpTextClient = JdkHttpTextClient(RuntimeHttpClient.create())

private[core] final class JdkHttpTextClient(
    client: HttpClient,
    hostGuard: String => Either[String, Unit] = NetworkTargetGuard.validateResolved(_),
    bodyTimeout: Duration = RuntimeHttpClient.requestTimeout
) extends HttpTextClient:

  private val maxResponseBytes = 4L * 1024L * 1024L

  def getTextWithProvenance(
      url: String
  ): Either[HttpTextError, HttpTextResponse] = RuntimeHttpClient.withSuccessfulStream(
    client,
    url,
    hostGuard,
    (message, provenance) => HttpTextError(url, message, provenance)
  ): result =>
    Try(RuntimeHttpBody.readWithDeadline(
      result.response.body(),
      bodyTimeout,
      s"text response body timed out after ${bodyTimeout.toSeconds}s"
    )(readBounded(_, maxResponseBytes))).toEither.left.map(Diagnostics.describe).flatten
      .map(text => HttpTextResponse(text, result.provenance))
      .left.map(message => HttpTextError(url, message, Some(result.provenance)))

  private def readBounded(input: InputStream, maxBytes: Long): Either[String, String] = Try:
    val output = ByteArrayOutputStream()
    val buffer = Array.ofDim[Byte](8192)
    var total  = 0L
    var count  = input.read(buffer)
    while count != -1 do
      total += count
      if total > maxBytes then
        throw IllegalArgumentException(s"text response exceeds max allowed $maxBytes bytes")
      output.write(buffer, 0, count)
      count = input.read(buffer)
    output.toString(StandardCharsets.UTF_8)
  match
    case Success(text)  => Right(text)
    case Failure(error) => Left(Diagnostics.describe(error))
