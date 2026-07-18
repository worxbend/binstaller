package binstaller.core

import java.io.InputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using

/** Expected failure from a binary metadata lookup. */
final case class BinaryMetadataError(
    url: String,
    message: String,
    provenance: Option[UrlProvenance] = None
)

/** Metadata observed for a downloadable artifact without materializing the body. */
final case class BinaryMetadata(
    sizeBytes: Option[Long],
    provenance: UrlProvenance,
    sha256: Option[Sha256Digest] = None
)

/** Boundary for resolving download URL provenance and content length for lock files. */
trait BinaryMetadataClient:
  /** Fetch metadata for a download URL. */
  def metadata(url: String): Either[BinaryMetadataError, BinaryMetadata]

/** Binary metadata client constructors. */
object BinaryMetadataClient:
  /** JDK HTTP implementation using HEAD with HTTPS, redirects, and timeout. */
  def jdk: BinaryMetadataClient = JdkBinaryMetadataClient(RuntimeHttpClient.create())

private[core] final class JdkBinaryMetadataClient(client: HttpClient) extends BinaryMetadataClient:

  private val maxBytes = BinaryDownloadLimits.default.maxBytes

  def metadata(url: String): Either[BinaryMetadataError, BinaryMetadata] =
    RuntimeUrl.httpsUri(url) match
      case Left(message) => Left(BinaryMetadataError(url, message))
      case Right(uri)    =>
        val request = HttpRequest
          .newBuilder(uri)
          .timeout(RuntimeHttpClient.requestTimeout)
          .GET()
          .build()
        Try(client.send(request, HttpResponse.BodyHandlers.ofInputStream())) match
          case Success(response) if response.statusCode() >= 200 && response.statusCode() < 300 =>
            val provenance = UrlProvenance.fromResponse(url, response)
            RuntimeUrl.httpsUri(provenance.finalUrl) match
              case Right(_)      => inspectBody(url, response.body(), provenance)
              case Left(message) => Left(BinaryMetadataError(url, message, Some(provenance)))
          case Success(response) =>
            val provenance = UrlProvenance.fromResponse(url, response)
            Left(BinaryMetadataError(url, s"HTTP ${response.statusCode()}", Some(provenance)))
          case Failure(error) => Left(BinaryMetadataError(url, error.getMessage))

  private def inspectBody(
      url: String,
      input: InputStream,
      provenance: UrlProvenance
  ): Either[BinaryMetadataError, BinaryMetadata] = Try:
    Using.resource(input)(Sha256.digestStream(_, maxBytes))
  match
    case Failure(error) => Left(BinaryMetadataError(url, error.getMessage, Some(provenance)))
    case Success(Left(message)) => Left(BinaryMetadataError(url, message, Some(provenance)))
    case Success(Right((digest, size))) => Right(BinaryMetadata(Some(size), provenance, Some(digest)))
