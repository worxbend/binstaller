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
  def getText(url: String): Either[HttpTextError, String]

  /** Fetch text and report the initial URL, final URL, and redirect chain. */
  def getTextWithProvenance(url: String): Either[HttpTextError, HttpTextResponse] =
    getText(url).map(text => HttpTextResponse(text, UrlProvenance.direct(url)))

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

  def getText(url: String): Either[HttpTextError, String] = getTextWithProvenance(url).map(_.text)

  override def getTextWithProvenance(
      url: String
  ): Either[HttpTextError, HttpTextResponse] = RuntimeUrl.httpsUri(url) match
    case Left(message) => Left(HttpTextError(url, message))
    case Right(_)      => Try(RuntimeHttpClient.getInputStream(client, url, hostGuard)) match
        case Success(Right(result))
            if result.response.statusCode() >= 200 &&
              result.response.statusCode() < 300 =>
          Try(RuntimeHttpBody.readWithDeadline(
            result.response.body(),
            bodyTimeout,
            s"text response body timed out after ${bodyTimeout.toSeconds}s"
          )(readBounded(_, maxResponseBytes))).toEither.left.map(Diagnostics.describe).flatten
            .map(text => HttpTextResponse(text, result.provenance))
            .left.map(message => HttpTextError(url, message, Some(result.provenance)))
        case Success(Right(result)) =>
          RuntimeHttpBody.closeAfterFailure(result.response.body())
          Left(HttpTextError(url, s"HTTP ${result.response.statusCode()}", Some(result.provenance)))
        case Success(Left(message)) => Left(HttpTextError(url, message))
        case Failure(error)         => Left(HttpTextError(url, Diagnostics.describe(error)))

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
