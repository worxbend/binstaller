package binstaller.core

import binstaller.config.Diagnostics
import binstaller.config.Sha256Digest

import java.io.InputStream
import java.net.http.HttpClient
import java.time.Duration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Expected failure from a binary metadata lookup. */
final case class BinaryMetadataError(
    url: String,
    message: String,
    provenance: Option[UrlProvenance] = None
)

/** What one inspection of a download URL observed. The body is read to hash it, then dropped. */
final case class BinaryMetadata(
    sizeBytes: Option[Long],
    provenance: UrlProvenance,
    sha256: Option[Sha256Digest] = None
)

/**
 * Boundary for inspecting a download URL for lock files: redirect provenance, size, and SHA-256.
 *
 * The name undersells the cost. A digest needs the bytes, so the production client downloads the
 * whole artifact on every call. `metadata` stays because implementations outside core rely on it.
 */
trait BinaryMetadataClient:
  /** Inspect a download URL. The production client streams the full artifact to hash it. */
  def metadata(url: String): Either[BinaryMetadataError, BinaryMetadata]

/** Binary metadata client constructors. */
object BinaryMetadataClient:
  /**
   * JDK HTTP implementation. Note: sha256 requires the artifact bytes, so this performs a full
   * streamed GET (hashing in a single pass), not a HEAD. Under `--locked` the artifact is fetched
   * here for lock validation and again by the installer; this keeps lock validation all-or-nothing
   * (every tool verified before any install) without holding every artifact on disk at once.
   */
  def jdk: BinaryMetadataClient = JdkBinaryMetadataClient(RuntimeHttpClient.create())

private[core] final class JdkBinaryMetadataClient(
    client: HttpClient,
    hostGuard: String => Either[String, Unit] = NetworkTargetGuard.validateResolved(_),
    bodyTimeout: Duration = BinaryDownloadLimits.default.bodyTimeout
) extends BinaryMetadataClient:

  private val maxBytes = BinaryDownloadLimits.default.maxBytes

  def metadata(url: String): Either[BinaryMetadataError, BinaryMetadata] =
    RuntimeHttpClient.withSuccessfulStream(
      client,
      url,
      hostGuard,
      (message, provenance) => BinaryMetadataError(url, message, provenance)
    ): result =>
      Try(RuntimeHttpBody.readWithDeadline(
        result.response.body(),
        bodyTimeout,
        BinaryMetadataError(
          url,
          s"metadata response body timed out after ${bodyTimeout.toSeconds}s",
          Some(result.provenance)
        )
      )(inspectBody(url, _, result.provenance))).toEither.left.map(error =>
        BinaryMetadataError(url, Diagnostics.describe(error), Some(result.provenance))
      ).flatten

  private def inspectBody(
      url: String,
      input: InputStream,
      provenance: UrlProvenance
  ): Either[BinaryMetadataError, BinaryMetadata] = Try:
    Sha256.digestStream(input, maxBytes)
  match
    case Failure(error) =>
      Left(BinaryMetadataError(url, Diagnostics.describe(error), Some(provenance)))
    case Success(Left(message))         => Left(BinaryMetadataError(url, message, Some(provenance)))
    case Success(Right((digest, size))) =>
      Right(BinaryMetadata(Some(size), provenance, Some(digest)))
