package binstaller.core

import binstaller.config.Sha256Digest

import java.security.MessageDigest
import java.io.InputStream

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
