package binstaller.core

import binstaller.config.ArchiveType
import utest.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Extracting archives safely: mappings, traversal refusal, and the expansion budget. */
object ArchiveExtractionTest extends TestSuite with CoreTestSupport:

  val tests: Tests = Tests:
    test("zip archive file mapping lands at configured relative target path"):
      val tempRoot   = tempDirectory("core-zip")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(zipArchive(Vector("pkg/alpha" -> "zip-alpha"))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.Zip,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "zip-alpha")

    test("tar.gz archive file mapping lands at configured relative target path"):
      val tempRoot   = tempDirectory("core-targz")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(tarGzArchive(Vector("pkg/alpha" -> "tar-alpha"))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "tar-alpha")

    test("tar.gz directory mapping moves extracted root directory into install root"):
      val tempRoot   = tempDirectory("core-targz-dir")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(tarGzArchive(Vector(
          "alpha-root/bin/alpha"    -> "alpha",
          "alpha-root/share/readme" -> "docs"
        ))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        directories = Vector("alpha-root" -> ".")
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "alpha")
      assert(Files.readString(installDir.resolve("share/readme")) == "docs")

    test("tar.gz root directory entries do not fail extraction"):
      val tempRoot   = tempDirectory("core-targz-root-dir")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(tarGzArchiveWithDirectories(
          directories = Vector("./"),
          files = Vector("./jj" -> "jujutsu")
        )),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("jj" -> "bin/jj"),
        executable = "bin/jj"
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/jj")) == "jujutsu")

    test("archive entries that escape staging are rejected and preserve existing install"):
      val tempRoot     = tempDirectory("core-zip-slip")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(zipArchive(Vector("../evil" -> "bad"))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.Zip,
        files = Vector("../evil" -> "bin/alpha")
      ))

      assert(result.left.exists(_.isInstanceOf[ToolInstallError.ArchiveExtractionFailed]))
      assert(Files.readString(existingFile) == "existing")

    test("duplicate zip archive members are rejected before replacement"):
      val tempRoot     = tempDirectory("core-zip-duplicate")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(zipArchiveWithDuplicateLocalEntries(Vector(
          "pkg/alpha" -> "first",
          "pkg/alpha" -> "second"
        ))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.Zip,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assert(result.left.exists:
        case ToolInstallError.ArchiveExtractionFailed(_, message) =>
          message.contains("duplicate archive member")
        case _ => false)
      assert(Files.readString(existingFile) == "existing")

    test("tar.gz hardlink metadata is rejected before replacement"):
      val tempRoot     = tempDirectory("core-targz-hardlink")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(tarGzArchiveWithEntryTypes(Vector(
          ("pkg/alpha", "", '1')
        ))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assert(result.left.exists:
        case ToolInstallError.ArchiveExtractionFailed(_, message) =>
          message.contains("unsafe archive link entry")
        case _ => false)
      assert(Files.readString(existingFile) == "existing")

    test("tar.xz extraction uses the validated in-process archive path"):
      val tempRoot        = tempDirectory("core-tarxz")
      val installDir      = tempRoot.resolve("zig")
      val commandExecutor = FakeArchiveCommandExecutor("zig-root/bin/zig", "zig")
      val installer       = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(tarXzArchive(Vector("zig-root/bin/zig" -> "zig"))),
        InstallFileSystem.nio,
        commandExecutor
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarXz,
        directories = Vector("zig-root" -> "."),
        executable = "bin/zig"
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/zig")) == "zig")
      assert(commandExecutor.commands.isEmpty)

    test("archive extraction enforces an aggregate expanded-byte budget"):
      assert(ArchiveExtractor.validateExtractedSize(ArchiveExtractor.maxExtractedBytes).isRight)
      assert(ArchiveExtractor.validateExtractedSize(ArchiveExtractor.maxExtractedBytes + 1).isLeft)

    test("tar.gz unplanned member exceeding the byte budget is rejected during the single pass"):
      // Regression guard for the decompression-bomb DoS: an unplanned member declaring more bytes
      // than the aggregate budget must be rejected even though NONE of the bomb members are part of
      // the copy plan. The previous two-pass extractor skipped unplanned members with no budget.
      val tempRoot   = tempDirectory("core-targz-bomb")
      val installDir = tempRoot.resolve("alpha")
      val output     = java.io.ByteArrayOutputStream()
      val gzip       = java.util.zip.GZIPOutputStream(output)
      val planned    = "pkg-alpha".getBytes(StandardCharsets.UTF_8)
      gzip.write(tarHeader("pkg/alpha", planned.length, '0'))
      gzip.write(planned)
      gzip.write(Array.fill[Byte]((512 - (planned.length % 512)) % 512)(0))
      // Unplanned member declaring > maxInflatedBytes (4 GiB) while carrying no payload; extraction
      // must reject it at the up-front inflate charge rather than skip-inflate it unbounded.
      gzip.write(tarHeader("pkg/bomb", 5000000000L, '0'))
      gzip.write(Array.fill[Byte](1024)(0))
      gzip.close()
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(output.toByteArray),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assert(result.left.exists:
        case ToolInstallError.ArchiveExtractionFailed(_, message) =>
          message.contains("inflated byte limit")
        case _ => false)

    test("tar.gz archive exceeding the max entry count is rejected"):
      val tempRoot   = tempDirectory("core-targz-count")
      val installDir = tempRoot.resolve("alpha")
      val output     = java.io.ByteArrayOutputStream()
      val gzip       = java.util.zip.GZIPOutputStream(output)
      val total      = ArchiveExtractor.maxEntries + 1
      var index      = 0
      while index < total do
        gzip.write(tarHeader(s"pkg/file-$index", 0, '0'))
        index += 1
      gzip.write(Array.fill[Byte](1024)(0))
      gzip.close()
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(output.toByteArray),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("pkg/file-0" -> "bin/alpha")
      ))

      assert(result.left.exists:
        case ToolInstallError.ArchiveExtractionFailed(_, message) =>
          message.contains("max entry count")
        case _ => false)

    test("tar.gz base-256 encoded entry size is decoded without a NumberFormatException"):
      val tempRoot   = tempDirectory("core-targz-base256")
      val installDir = tempRoot.resolve("alpha")
      val content    = "base256".getBytes(StandardCharsets.UTF_8)
      val header     = tarHeader("pkg/alpha", 0, '0')
      // Overwrite the 12-byte size field (offset 124) with a GNU base-256 big-endian encoding: the
      // high bit of the first byte marks base-256, the value follows big-endian in the low bytes.
      header(124) = 0x80.toByte
      var index = 1
      while index < 12 do
        header(124 + index) = 0.toByte
        index += 1
      header(124 + 11) = content.length.toByte
      val output = java.io.ByteArrayOutputStream()
      val gzip   = java.util.zip.GZIPOutputStream(output)
      gzip.write(header)
      gzip.write(content)
      gzip.write(Array.fill[Byte]((512 - (content.length % 512)) % 512)(0))
      gzip.write(Array.fill[Byte](1024)(0))
      gzip.close()
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(output.toByteArray),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "base256")
