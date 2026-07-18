package binstaller.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using

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

private[core] final class JdkHttpTextClient(client: HttpClient) extends HttpTextClient:

  private val maxResponseBytes = 4L * 1024L * 1024L

  def getText(url: String): Either[HttpTextError, String] = getTextWithProvenance(url).map(_.text)

  override def getTextWithProvenance(
      url: String
  ): Either[HttpTextError, HttpTextResponse] = RuntimeUrl.httpsUri(url) match
    case Left(message) => Left(HttpTextError(url, message))
    case Right(uri)    =>
      val request = HttpRequest.newBuilder(uri).timeout(RuntimeHttpClient.requestTimeout).GET()
        .build()
      Try(client.send(request, HttpResponse.BodyHandlers.ofInputStream())) match
        case Success(response) if response.statusCode() >= 200 && response.statusCode() < 300 =>
          val provenance = UrlProvenance.fromResponse(url, response)
          RuntimeUrl.httpsUri(provenance.finalUrl) match
            case Right(_)      => readBounded(response.body(), maxResponseBytes)
                .map(text => HttpTextResponse(text, provenance))
                .left
                .map(message => HttpTextError(url, message, Some(provenance)))
            case Left(message) => Left(HttpTextError(url, message, Some(provenance)))
        case Success(response) =>
          val provenance = UrlProvenance.fromResponse(url, response)
          Left(HttpTextError(url, s"HTTP ${response.statusCode()}", Some(provenance)))
        case Failure(error) => Left(HttpTextError(url, error.getMessage))

  private def readBounded(input: InputStream, maxBytes: Long): Either[String, String] = Try:
    Using.resource(input): stream =>
      val output = ByteArrayOutputStream()
      val buffer = Array.ofDim[Byte](8192)
      var total  = 0L
      var count  = stream.read(buffer)
      while count != -1 do
        total += count
        if total > maxBytes then
          throw IllegalArgumentException(s"text response exceeds max allowed $maxBytes bytes")
        output.write(buffer, 0, count)
        count = stream.read(buffer)
      output.toString(StandardCharsets.UTF_8)
  match
    case Success(text)  => Right(text)
    case Failure(error) => Left(error.getMessage)
