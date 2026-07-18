package binstaller.core

import java.io.InputStream
import java.net.http.HttpClient
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
  /** JDK HTTP implementation. Note: sha256 requires the artifact bytes, so this performs a full
   *  streamed GET (hashing in a single pass), not a HEAD. Under `--locked` the artifact is fetched
   *  here for lock validation and again by the installer; this keeps lock validation all-or-nothing
   *  (every tool verified before any install) without holding every artifact on disk at once. */
  def jdk: BinaryMetadataClient = JdkBinaryMetadataClient(RuntimeHttpClient.create())

private[core] final class JdkBinaryMetadataClient(
    client: HttpClient,
    hostGuard: String => Either[String, Unit] = NetworkTargetGuard.validateResolved
) extends BinaryMetadataClient:

  private val maxBytes = BinaryDownloadLimits.default.maxBytes

  def metadata(url: String): Either[BinaryMetadataError, BinaryMetadata] =
    RuntimeUrl.httpsUri(url) match
      case Left(message) => Left(BinaryMetadataError(url, message))
      case Right(_)      => Try(RuntimeHttpClient.getInputStream(client, url, hostGuard)) match
          case Success(Right(result))
              if result.response.statusCode() >= 200 &&
                result.response.statusCode() < 300 =>
            inspectBody(url, result.response.body(), result.provenance)
          case Success(Right(result)) =>
            result.response.body().close()
            Left(BinaryMetadataError(
              url,
              s"HTTP ${result.response.statusCode()}",
              Some(result.provenance)
            ))
          case Success(Left(message)) => Left(BinaryMetadataError(url, message))
          case Failure(error)         => Left(BinaryMetadataError(url, error.getMessage))

  private def inspectBody(
      url: String,
      input: InputStream,
      provenance: UrlProvenance
  ): Either[BinaryMetadataError, BinaryMetadata] = Try:
    Using.resource(input)(Sha256.digestStream(_, maxBytes))
  match
    case Failure(error) => Left(BinaryMetadataError(url, error.getMessage, Some(provenance)))
    case Success(Left(message))         => Left(BinaryMetadataError(url, message, Some(provenance)))
    case Success(Right((digest, size))) =>
      Right(BinaryMetadata(Some(size), provenance, Some(digest)))
