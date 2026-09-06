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

    test("normalized duplicate explicit sources are rejected before replacement"):
      val tempRoot     = tempDirectory("core-zip-duplicate-source")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(zipArchive(Vector("pkg/alpha" -> "replacement"))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.Zip,
        files = Vector("pkg/alpha" -> "bin/alpha", "pkg/./alpha" -> "copy/alpha")
      ))

      assert(result.left.exists:
        case ToolInstallError.ArchiveExtractionFailed(_, message) =>
          message.contains("duplicate archive source")
        case _ => false)
      assert(Files.readString(existingFile) == "existing")

    test("normalized duplicate directory sources are rejected before replacement"):
      val tempRoot     = tempDirectory("core-zip-duplicate-directory-source")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(zipArchive(Vector("pkg/alpha" -> "replacement"))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.Zip,
        directories = Vector("pkg" -> "bin", "pkg/./" -> "copy")
      ))

      assert(result.left.exists:
        case ToolInstallError.ArchiveExtractionFailed(_, message) =>
          message.contains("duplicate archive source: pkg")
        case _ => false)
      assert(Files.readString(existingFile) == "existing")
      assert(!hasStagedInstall(tempRoot, "alpha"))

    test("the same normalized source cannot be declared as both a file and a directory"):
      val tempRoot  = tempDirectory("core-zip-conflicting-source")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(zipArchive(Vector("pkg/alpha" -> "replacement"))),
        InstallFileSystem.nio
      )
      val result = installer.installTool(archiveTool(
        tempRoot.resolve("alpha"),
        ArchiveType.Zip,
        files = Vector("pkg/alpha" -> "bin/alpha"),
        directories = Vector("pkg/./alpha" -> "copy")
      ))

      assert(result.left.exists:
        case ToolInstallError.ArchiveExtractionFailed(_, message) =>
          message.contains("duplicate archive source: pkg/alpha")
        case _ => false)
      assert(!Files.exists(tempRoot.resolve("alpha")))
      assert(!hasStagedInstall(tempRoot, "alpha"))

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

    test("tar.gz GNU long-name member is extracted under its full path"):
      // Regression guard for issue #2: GNU tar stores a path longer than the 100-byte header field
      // in a preceding "@LongLink" pseudo-entry (typeflag 'L'), which the reader used to reject as
      // an unsupported entry type before ever reaching the member it names.
      val tempRoot   = tempDirectory("core-targz-longname")
      val installDir = tempRoot.resolve("alpha")
      val longName   = s"pkg/${"nested/" * 15}alpha"
      assert(longName.length > 100)

      val result = installArchive(
        installDir,
        tarGzArchiveWithLongNames(Vector(longName -> "long-alpha")),
        files = Vector(longName -> "bin/alpha")
      )

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "long-alpha")

    test("tar.xz directory mapping extracts members whose names arrive via @LongLink"):
      // The exact shape of the zig release artifact: a tar.xz whose tree is pulled in by a single
      // directory mapping and whose deepest paths are carried by GNU long-name headers.
      val tempRoot        = tempDirectory("core-tarxz-longname")
      val installDir      = tempRoot.resolve("zig")
      val longName        = s"zig-root/lib/libc/include/${"generic-freebsd/" * 6}header.h"
      val commandExecutor = FakeArchiveCommandExecutor("zig-root/bin/zig", "zig")
      assert(longName.length > 100)

      val result = installArchive(
        installDir,
        tarXzArchiveWithLongNames(Vector("zig-root/bin/zig" -> "zig", longName -> "header")),
        archiveType = ArchiveType.TarXz,
        files = Vector.empty,
        directories = Vector("zig-root" -> "."),
        executable = "bin/zig",
        commandExecutor = commandExecutor
      )

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/zig")) == "zig")
      assert(Files.readString(installDir.resolve(longName.stripPrefix("zig-root/"))) == "header")
      assert(commandExecutor.commands.isEmpty)

    test("tar.gz metadata payload larger than the cap is rejected before allocation"):
      // A metadata payload is read whole, ahead of any byte budget, so its own cap is what stops a
      // header that declares a gigabyte of "path".
      val tempRoot   = tempDirectory("core-targz-longname-huge")
      val installDir = tempRoot.resolve("alpha")
      // Written by hand: the header declares a gigabyte of payload and then supplies none of it,
      // which is precisely the archive a well-formed builder would refuse to produce.
      val archive = gzippedTar: gzip =>
        gzip.write(tarHeader("././@LongLink", 1024L * 1024L * 1024L, 'L'))

      val result = installArchive(installDir, archive)

      assertArchiveExtractionFailed(result, "tar metadata entry exceeds")

    test("tar.gz stream of nothing but long-name headers trips the entry budget"):
      // Long-name headers never reach a member, so they must be counted as they are read or a
      // crafted archive would spin forever without ever charging the entry or time budget.
      val tempRoot   = tempDirectory("core-targz-longname-count")
      val installDir = tempRoot.resolve("alpha")
      // Streamed straight into the gzip stream: this is roughly 64 MiB of uncompressed tar.
      val archive = gzippedTar: gzip =>
        val total = ArchiveExtractor.maxEntries + 1
        var index = 0
        while index < total do
          gzip.write(tarHeader("././@LongLink", 6, 'L'))
          gzip.write("pkg/a".getBytes(StandardCharsets.UTF_8) :+ 0.toByte)
          gzip.write(Array.fill[Byte](506)(0))
          index += 1

      val result = installArchive(installDir, archive)

      assertArchiveExtractionFailed(result, "max entry count")

    test("tar.gz long link-target metadata still rejects the link it names"):
      // A 'K' header carries a long link target; consuming its payload is what lets the link entry
      // that follows be reported as a link rather than as garbled header bytes.
      val tempRoot   = tempDirectory("core-targz-longlink-target")
      val installDir = tempRoot.resolve("alpha")
      val target     = ("../" * 40) + "etc/passwd"
      val bytes      = target.getBytes(StandardCharsets.UTF_8) :+ 0.toByte
      val archive    = gzippedTar: gzip =>
        gzip.write(tarHeader("././@LongLink", bytes.length, 'K'))
        gzip.write(bytes)
        gzip.write(Array.fill[Byte]((512 - (bytes.length % 512)) % 512)(0))
        gzip.write(tarHeader("pkg/alpha", 0, '2'))

      val result = installArchive(installDir, archive)

      assertArchiveExtractionFailed(result, "unsafe archive link entry: pkg/alpha")

    test("tar.gz PAX extended header supplies the member's full path"):
      // bsdtar and Go's archive/tar solve the same 100-byte problem with a PAX extended header
      // (typeflag 'x') carrying a "path" attribute instead of a GNU @LongLink pseudo-entry.
      val tempRoot   = tempDirectory("core-targz-pax")
      val installDir = tempRoot.resolve("alpha")
      val longName   = s"pkg/${"nested/" * 15}alpha"
      assert(longName.length > 100)

      val result = installArchive(
        installDir,
        tarGzArchiveWithPaxNames(Vector(longName -> "pax-alpha")),
        files = Vector(longName -> "bin/alpha")
      )

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "pax-alpha")

    test("tar.gz PAX global header is consumed without disturbing the members after it"):
      // A global header describes the archive, not the next member — git archive writes one
      // carrying only a comment. It must be skipped whole, records and all.
      val tempRoot   = tempDirectory("core-targz-pax-global")
      val installDir = tempRoot.resolve("alpha")
      val archive    = gzippedTar: gzip =>
        writePaxHeader(gzip, 'g', Vector("comment" -> ("0" * 40)))
        writePaxHeader(gzip, 'x', Vector("path" -> "pkg/alpha"))
        writeTarMember(gzip, "pkg/alpha", "global-alpha")

      val result = installArchive(installDir, archive)

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "global-alpha")

    test("tar.gz PAX size attribute overrides the header size field"):
      // A member too large for the 12-byte octal size field declares its real length in PAX.
      // Ignoring that would resume reading the next header from the middle of the payload.
      val tempRoot   = tempDirectory("core-targz-pax-size")
      val installDir = tempRoot.resolve("alpha")
      val payload    = "pax-sized".getBytes(StandardCharsets.UTF_8)
      val archive    = gzippedTar: gzip =>
        writePaxHeader(gzip, 'x', Vector("size" -> payload.length.toString))
        // Written by hand: the ustar header understates the member, exactly as it must when the
        // real size does not fit the octal field.
        gzip.write(tarHeader("pkg/alpha", 0, '0'))
        gzip.write(payload)
        gzip.write(Array.fill[Byte]((512 - (payload.length % 512)) % 512)(0))

      val result = installArchive(installDir, archive)

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "pax-sized")

    test("tar.gz PAX record whose length prefix is a lie is rejected"):
      // A record length that does not match its own bytes would walk the parser off the record
      // boundary; it is refused rather than salvaged.
      val tempRoot   = tempDirectory("core-targz-pax-malformed")
      val installDir = tempRoot.resolve("alpha")
      // Written by hand: the "99" length prefix is the lie under test, so the record must not go
      // through the builder that would compute a truthful one.
      val payload = "99 path=pkg/alpha\n".getBytes(StandardCharsets.UTF_8)
      val archive = gzippedTar: gzip =>
        gzip.write(tarHeader("PaxHeaders.0/entry", payload.length, 'x'))
        gzip.write(payload)
        gzip.write(Array.fill[Byte]((512 - (payload.length % 512)) % 512)(0))
        gzip.write(tarHeader("pkg/alpha", 0, '0'))

      val result = installArchive(installDir, archive)

      assertArchiveExtractionFailed(result, "malformed tar extended header record")

    test("archive extraction enforces an aggregate expanded-byte budget"):
      assert(ArchiveExtractor.validateExtractedSize(ArchiveExtractor.maxExtractedBytes).isRight)
      assert(ArchiveExtractor.validateExtractedSize(ArchiveExtractor.maxExtractedBytes + 1).isLeft)

    test("file and directory fan-out charges both disk copies but inflates once"):
      val tempRoot = tempDirectory("core-archive-fanout-budget")
      val artifact = tempRoot.resolve("alpha.tar.gz")
      val staging  = tempRoot.resolve("stage")
      val payload  = "tool-bytes".getBytes(StandardCharsets.UTF_8)
      Files.write(artifact, tarGzArchive(Vector("pkg/tool" -> "tool-bytes")))
      Files.createDirectory(staging)
      val archive = archiveTool(
        tempRoot.resolve("install"),
        ArchiveType.TarGz,
        files = Vector("pkg/tool" -> "bin/alpha"),
        directories = Vector("pkg" -> "share")
      ).download.archive.get
      val limits = ArchiveExtractionLimits(
        maxExtractedBytes = payload.length.toLong * 2L - 1L,
        maxInflatedBytes = payload.length.toLong,
        maxEntries = 10,
        timeBudgetMillis = 5_000L
      )

      val result = ArchiveExtractor.extractFile(archive, artifact, staging, limits)

      assert(result.left.exists(_.contains("extracted byte limit")))
      assert(!result.left.exists(_.contains("inflated byte limit")))
      val sufficientStaging = Files.createDirectory(tempRoot.resolve("sufficient-stage"))
      val sufficient        = ArchiveExtractor.extractFile(
        archive,
        artifact,
        sufficientStaging,
        limits.copy(maxExtractedBytes = payload.length.toLong * 2L)
      )
      assert(sufficient.isRight)
      assert(Files.readString(sufficientStaging.resolve("bin/alpha")) == "tool-bytes")
      assert(Files.readString(sufficientStaging.resolve("share/tool")) == "tool-bytes")

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
