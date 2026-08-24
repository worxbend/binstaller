package binstaller.config

/** A validated, lowercase SHA-256 digest.
 *
 *  The one place the `^[0-9a-f]{64}$` rule lives. It used to be written four separate times — in
 *  the manifest decoder, in the `SHA256SUMS` parser, in locked-apply validation, and here — with
 *  three of those accepting mixed case and the fourth normalizing it. Comparisons then had to use
 *  `equalsIgnoreCase`, including in the checksum verification that decides whether a downloaded
 *  binary is trusted, where comparing a normalized value against an unnormalized one is exactly
 *  the sort of near-miss that is easy to get wrong and hard to notice.
 *
 *  Normalizing at construction makes `==` correct everywhere, so no call site has to remember.
 *  It lives in `config`, the lowest module, because both the manifest and the install pipeline
 *  need it.
 */
final case class Sha256Digest private (value: String):

  /** Renders as the bare hex, so interpolating a digest into a diagnostic reads unchanged. */
  override def toString: String = value

/** SHA-256 digest validation and construction. */
object Sha256Digest:

  /** Parse 64 hexadecimal characters, in either case, into a lowercase digest. */
  def fromString(value: String): Either[String, Sha256Digest] =
    val normalized = value.toLowerCase
    if normalized.matches("^[0-9a-f]{64}$") then Right(Sha256Digest(normalized))
    else Left("sha256 digest must be 64 hexadecimal characters")

  /** Wrap a digest this program just computed itself. */
  private[binstaller] def trusted(value: String): Sha256Digest = Sha256Digest(value.toLowerCase)
