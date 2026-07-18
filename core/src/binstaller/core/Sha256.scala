package binstaller.core

import java.security.MessageDigest
import java.io.InputStream

/** Validated lowercase SHA-256 digest. */
final case class Sha256Digest private (value: String)

/** SHA-256 digest validation and construction. */
object Sha256Digest:

  def fromString(value: String): Either[String, Sha256Digest] =
    val normalized = value.toLowerCase
    if normalized.matches("^[0-9a-f]{64}$") then Right(Sha256Digest(normalized))
    else Left("sha256 digest must be 64 hexadecimal characters")

  private[core] def trusted(value: String): Sha256Digest = Sha256Digest(value.toLowerCase)

private[core] object Sha256:

  def digest(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    hex(digest)

  def digestStream(input: InputStream, maxBytes: Long): Either[String, (Sha256Digest, Long)] =
    val messageDigest = MessageDigest.getInstance("SHA-256")
    val buffer        = Array.ofDim[Byte](64 * 1024)
    var total         = 0L
    var count         = input.read(buffer)
    while count != -1 do
      total += count
      if total > maxBytes then
        return Left(s"download size $total exceeds max allowed $maxBytes bytes")
      messageDigest.update(buffer, 0, count)
      count = input.read(buffer)
    Right(Sha256Digest.trusted(hex(messageDigest.digest())) -> total)

  private def hex(bytes: Array[Byte]): String = bytes.map(byte => f"${byte & 0xff}%02x").mkString
