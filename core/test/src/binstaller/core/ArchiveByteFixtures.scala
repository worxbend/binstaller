package binstaller.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import scala.util.Using

/** Byte-level builders for the zip, tar, tar.gz and tar.xz fixtures the extraction tests stream. */
private[core] trait ArchiveByteFixtures:

  protected def sha256(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(byte => f"${byte & 0xff}%02x").mkString

  protected def zipArchive(entries: Vector[(String, String)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    Using.resource(ZipOutputStream(output)): zip =>
      entries.foreach:
        case (name, content) =>
          zip.putNextEntry(ZipEntry(name))
          zip.write(content.getBytes(StandardCharsets.UTF_8))
          zip.closeEntry()
    output.toByteArray

  protected def zipArchiveWithDuplicateLocalEntries(entries: Vector[(String, String)])
      : Array[Byte] =
    val output = ByteArrayOutputStream()
    entries.foreach:
      case (name, content) =>
        val nameBytes    = name.getBytes(StandardCharsets.UTF_8)
        val contentBytes = content.getBytes(StandardCharsets.UTF_8)
        val crc          = CRC32()
        crc.update(contentBytes)
        writeLittleInt(output, 0x04034b50)
        writeLittleShort(output, 20)
        writeLittleShort(output, 0)
        writeLittleShort(output, 0)
        writeLittleShort(output, 0)
        writeLittleShort(output, 0)
        writeLittleInt(output, crc.getValue.toInt)
        writeLittleInt(output, contentBytes.length)
        writeLittleInt(output, contentBytes.length)
        writeLittleShort(output, nameBytes.length)
        writeLittleShort(output, 0)
        output.write(nameBytes)
        output.write(contentBytes)
    output.toByteArray

  protected def writeLittleShort(output: ByteArrayOutputStream, value: Int): Unit =
    output.write(value & 0xff)
    output.write((value >>> 8) & 0xff)

  protected def writeLittleInt(output: ByteArrayOutputStream, value: Int): Unit =
    writeLittleShort(output, value & 0xffff)
    writeLittleShort(output, (value >>> 16) & 0xffff)

  protected def tarGzArchive(entries: Vector[(String, String)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    val gzip   = GZIPOutputStream(output)
    entries.foreach:
      case (name, content) =>
        val bytes = content.getBytes(StandardCharsets.UTF_8)
        gzip.write(tarHeader(name, bytes.length, '0'))
        gzip.write(bytes)
        val padding = (512 - (bytes.length % 512)) % 512
        gzip.write(Array.fill[Byte](padding)(0))
    gzip.write(Array.fill[Byte](1024)(0))
    gzip.close()
    output.toByteArray

  protected def tarXzArchive(entries: Vector[(String, String)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    val xz     = XZOutputStream(output, LZMA2Options())
    entries.foreach:
      case (name, content) =>
        val bytes = content.getBytes(StandardCharsets.UTF_8)
        xz.write(tarHeader(name, bytes.length, '0'))
        xz.write(bytes)
        val padding = (512 - (bytes.length % 512)) % 512
        xz.write(Array.fill[Byte](padding)(0))
    xz.write(Array.fill[Byte](1024)(0))
    xz.close()
    output.toByteArray

  protected def tarGzArchiveWithDirectories(
      directories: Vector[String],
      files: Vector[(String, String)]
  ): Array[Byte] =
    val output = ByteArrayOutputStream()
    val gzip   = GZIPOutputStream(output)
    directories.foreach: name =>
      gzip.write(tarHeader(name, 0, '5'))
    files.foreach:
      case (name, content) =>
        val bytes = content.getBytes(StandardCharsets.UTF_8)
        gzip.write(tarHeader(name, bytes.length, '0'))
        gzip.write(bytes)
        val padding = (512 - (bytes.length % 512)) % 512
        gzip.write(Array.fill[Byte](padding)(0))
    gzip.write(Array.fill[Byte](1024)(0))
    gzip.close()
    output.toByteArray

  /**
   * A tar.gz whose names are carried by GNU "@LongLink" pseudo-entries, exactly as GNU tar writes a
   * path that does not fit the 100-byte header field: the real header keeps only the truncated
   * name, so a reader that ignores the pseudo-entry sees the wrong member.
   */
  protected def tarGzArchiveWithLongNames(entries: Vector[(String, String)]): Array[Byte] =
    gzipped(longNameTarBytes(entries))

  /** The tar.xz form of [[tarGzArchiveWithLongNames]] — the shape zig's release artifact has. */
  protected def tarXzArchiveWithLongNames(entries: Vector[(String, String)]): Array[Byte] =
    xzCompressed(longNameTarBytes(entries))

  protected def longNameTarBytes(entries: Vector[(String, String)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    entries.foreach:
      case (name, content) =>
        writeLongNameEntry(output, name)
        writeTarMember(output, name, content)
    output.write(Array.fill[Byte](1024)(0))
    output.toByteArray

  /**
   * A tar.gz whose names arrive in PAX extended headers (typeflag 'x' with a "path" attribute), as
   * bsdtar and Go's archive/tar write them. As with the GNU form, the real header keeps only the
   * truncated name.
   */
  protected def tarGzArchiveWithPaxNames(entries: Vector[(String, String)]): Array[Byte] =
    gzipped(paxTarBytes(entries))

  protected def paxTarBytes(entries: Vector[(String, String)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    entries.foreach:
      case (name, content) =>
        writePaxHeader(output, 'x', Vector("path" -> name))
        writeTarMember(output, name, content)
    output.write(Array.fill[Byte](1024)(0))
    output.toByteArray

  /** One PAX extended header member: typeflag 'x' (per-entry) or 'g' (whole archive). */
  protected def writePaxHeader(
      output: OutputStream,
      entryType: Char,
      records: Vector[(String, String)]
  ): Unit =
    val payload = records.foldLeft(Array.empty[Byte]):
      case (accumulated, (key, value)) => accumulated ++ paxRecord(key, value)
    output.write(tarHeader("PaxHeaders.0/entry", payload.length, entryType))
    output.write(payload)
    output.write(Array.fill[Byte]((512 - (payload.length % 512)) % 512)(0))

  /**
   * One "<length> <key>=<value>\n" record. The length counts its own digits, so it is solved for
   * rather than computed once.
   */
  protected def paxRecord(key: String, value: String): Array[Byte] =
    val body   = s" $key=$value\n".getBytes(StandardCharsets.UTF_8)
    var length = body.length + 1
    while length.toString.length + body.length != length do
      length = length.toString.length + body.length
    length.toString.getBytes(StandardCharsets.UTF_8) ++ body

  /** One GNU long-name pseudo-entry: typeflag 'L' with the NUL-terminated real path as payload. */
  protected def writeLongNameEntry(output: OutputStream, name: String): Unit =
    val bytes = name.getBytes(StandardCharsets.UTF_8) :+ 0.toByte
    output.write(tarHeader("././@LongLink", bytes.length, 'L'))
    output.write(bytes)
    output.write(Array.fill[Byte]((512 - (bytes.length % 512)) % 512)(0))

  protected def gzipped(bytes: Array[Byte]): Array[Byte] =
    val output = ByteArrayOutputStream()
    val gzip   = GZIPOutputStream(output)
    gzip.write(bytes)
    gzip.close()
    output.toByteArray

  /**
   * One ustar file member: the header (whose name field keeps only the first 100 bytes, as the
   * format forces) followed by the payload padded out to the 512-byte record boundary.
   */
  protected def writeTarMember(output: OutputStream, name: String, content: String): Unit =
    val bytes = content.getBytes(StandardCharsets.UTF_8)
    output.write(tarHeader(name.take(100), bytes.length, '0'))
    output.write(bytes)
    output.write(Array.fill[Byte]((512 - (bytes.length % 512)) % 512)(0))

  /**
   * Gzip whatever `write` puts into the tar, closing the archive with the two zero blocks that end
   * it. The writer is handed the compressing stream itself rather than a buffer: the entry-budget
   * fixture pushes tens of megabytes through it, which must never be staged uncompressed.
   *
   * Only the well-formed scaffolding belongs here. A test whose subject is a malformed header or
   * record still writes those bytes by hand inside the callback.
   */
  protected def gzippedTar(write: OutputStream => Unit): Array[Byte] =
    val output = ByteArrayOutputStream()
    val gzip   = GZIPOutputStream(output)
    try
      write(gzip)
      gzip.write(Array.fill[Byte](1024)(0))
    finally gzip.close()
    output.toByteArray

  protected def xzCompressed(bytes: Array[Byte]): Array[Byte] =
    val output = ByteArrayOutputStream()
    val xz     = XZOutputStream(output, LZMA2Options())
    xz.write(bytes)
    xz.close()
    output.toByteArray

  protected def tarGzArchiveWithEntryTypes(entries: Vector[(String, String, Char)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    val gzip   = GZIPOutputStream(output)
    entries.foreach:
      case (name, content, entryType) =>
        val bytes = content.getBytes(StandardCharsets.UTF_8)
        gzip.write(tarHeader(name, bytes.length, entryType))
        if entryType == '0' then
          gzip.write(bytes)
          val padding = (512 - (bytes.length % 512)) % 512
          gzip.write(Array.fill[Byte](padding)(0))
    gzip.write(Array.fill[Byte](1024)(0))
    gzip.close()
    output.toByteArray

  protected def tarHeader(name: String, size: Long, entryType: Char): Array[Byte] =
    val header = Array.fill[Byte](512)(0)
    writeTarField(header, 0, 100, name)
    writeTarField(header, 124, 12, f"$size%011o")
    header(156) = entryType.toByte
    header

  protected def writeTarField(header: Array[Byte], offset: Int, length: Int, value: String): Unit =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    Array.copy(bytes, 0, header, offset, math.min(bytes.length, length))
