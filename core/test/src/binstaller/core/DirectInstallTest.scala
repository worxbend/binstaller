package binstaller.core

import binstaller.config.ChecksumAlgorithm
import binstaller.config.ExecutableMode
import binstaller.config.ArchiveType
import utest.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Installing a resolved plan: staging, replacement, failure handling and parallelism. */
object DirectInstallTest extends TestSuite with CoreTestSupport:

  val tests: Tests = Tests:
    test("direct binary install writes download bytes to first executable path"):
      val tempRoot   = tempDirectory("core-direct")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes),
        InstallFileSystem.nio
      )

      val result = installer.installTool(directTool(installDir))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "alpha-binary")

    test("sha256 mismatch fails before replacing an existing install"):
      val tempRoot     = tempDirectory("core-checksum")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val tool = directTool(
        installDir,
        checksum = Some(ResolvedChecksum(
          ChecksumAlgorithm.Sha256,
          digest("0" * 64),
          ResolvedChecksumSource.Configured
        ))
      )
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("replacement".getBytes),
        InstallFileSystem.nio
      )

      val result = installer.installTool(tool)

      assert(result.isLeft)
      assert(result.left.exists(_.isInstanceOf[ToolInstallError.ChecksumMismatch]))
      assert(Files.readString(existingFile) == "existing")

    test("executable modes use four-digit octal strings and default to 0755"):
      val fileSystem = RecordingInstallFileSystem(stagedFiles = Vector("bin/alpha", "bin/helper"))
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha".getBytes),
        fileSystem
      )
      val tool = directTool(
        Path.of("/tmp/alpha"),
        executables = Vector(
          ResolvedExecutable("bin/alpha", ExecutableMode.fromString("0700").toOption),
          ResolvedExecutable("bin/helper", None)
        )
      )

      val result = installer.installTool(tool)

      assertInstallSuccess(result, "/tmp/alpha")
      assert(fileSystem.recordedModes.map(request => request.path -> request.mode.octal) ==
        Vector("bin/alpha" -> "0700", "bin/helper" -> "0755"))
      assert(fileSystem.recordedModes.map(_.mode.numeric) == Vector(448, 493))

    test("download failure preserves existing install and returns a typed error"):
      val tempRoot     = tempDirectory("core-download")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.failure("network unavailable"),
        InstallFileSystem.nio
      )

      val result = installer.installTool(directTool(installDir))

      assert(result ==
        Left(
          ToolInstallError.DownloadFailed(
            toolName("alpha"),
            "https://example.invalid/alpha",
            "network unavailable"
          )
        ))
      assert(Files.readString(existingFile) == "existing")

    test("staging failure preserves existing install and does not replace"):
      val tempRoot     = tempDirectory("core-staging")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val fileSystem = RecordingInstallFileSystem(stageFailure = Some("disk full"))
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("replacement".getBytes),
        fileSystem
      )

      val result = installer.installTool(directTool(installDir))

      assert(result == Left(ToolInstallError.StagingFailed(toolName("alpha"), "disk full")))
      assert(fileSystem.replaceCalls == 0)
      assert(Files.readString(existingFile) == "existing")

    test("mode application failure preserves existing install and does not replace"):
      val tempRoot     = tempDirectory("core-mode")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val fileSystem = RecordingInstallFileSystem(modeFailure = Some("permission denied"))
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("replacement".getBytes),
        fileSystem
      )

      val result = installer.installTool(directTool(installDir))

      assert(result ==
        Left(
          ToolInstallError.ModeApplicationFailed(
            toolName("alpha"),
            "bin/alpha",
            "0755",
            "permission denied"
          )
        ))
      assert(fileSystem.replaceCalls == 0)
      assert(Files.readString(existingFile) == "existing")

    test("apply renders expected executor failures without throwing"):
      val tempRoot = tempDirectory("core-cli-error")
      val config   = tempRoot.resolve("profile.yaml")
      Files.writeString(config, directBinaryYaml(tempRoot.resolve("alpha")))
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(
          FakeBinaryDownloadClient.failure("network unavailable"),
          InstallFileSystem.nio
        )
      )

      val result = service.apply(
        InstallerOptions(
          configPath = config.toString,
          statePath = None,
          resetState = ResetState.Disabled,
          verboseOutput = VerboseOutput.Disabled
        )
      )

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.contains("failed alpha: download:")))
      assert(result.lines.exists(_.contains("network unavailable")))
      assert(!result.lines.exists(_.contains("Exception")))
      assert(!result.lines.exists(_.contains("at binstaller.")))

    test("apply installs a resolved plan"):
      val tempRoot   = tempDirectory("core-confirm")
      val installDir = tempRoot.resolve("alpha")
      val config     = writeConfig(tempRoot, directBinaryYaml(installDir))
      val service    = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = service.apply(applyOptions(config))

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(Files.exists(installDir.resolve("bin/alpha")))

    test("continueOnError false stops apply after the first failed tool"):
      val tempRoot = tempDirectory("core-stop-on-error")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "stop.state.json"))
      val service  = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Left("network unavailable"),
          "https://example.invalid/beta"  -> Right("beta".getBytes(StandardCharsets.UTF_8))
        ))
      )

      val result = service.apply(applyOptions(config))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.startsWith("failed alpha: download:")))
      assert(!result.lines.exists(_.contains("installed beta")))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))
      assert(!hasStagedInstall(tempRoot, "beta"))

    test("continueOnError true continues apply after failed tools"):
      val tempRoot = tempDirectory("core-continue-on-error")
      val config   = writeConfig(
        tempRoot,
        twoToolYaml(tempRoot, "continue.state.json", continueOnError = true)
      )
      val service = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Left("network unavailable"),
          "https://example.invalid/beta"  -> Right("beta".getBytes(StandardCharsets.UTF_8))
        ))
      )

      val result = service.apply(applyOptions(config))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.startsWith("failed alpha: download:")))
      assert(result.lines.exists(_.contains("installed beta")))
      assert(Files.isRegularFile(tempRoot.resolve("apps/beta/bin/beta")))

    test("apply downloads and stages tools concurrently up to configured parallelism"):
      val tempRoot = tempDirectory("core-parallel-downloads")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "parallel.state.json"))
      val client   = ConcurrentTrackingDownloadClient(
        Vector("https://example.invalid/alpha", "https://example.invalid/beta")
      )
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(client, InstallFileSystem.nio),
        ApplyStateStore.nio(tempRoot)
      )

      val result = service.apply(applyOptions(config).copy(applyParallelism = parallelism(2)))

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(client.maxInFlight >= 2)
      assert(Files.isRegularFile(tempRoot.resolve("apps/alpha/bin/alpha")))
      assert(Files.isRegularFile(tempRoot.resolve("apps/beta/bin/beta")))

    test("service honors apply parallelism of one"):
      val tempRoot = tempDirectory("core-serial-downloads")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "serial.state.json"))
      val client   = ParallelismProbeDownloadClient()
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(client, InstallFileSystem.nio),
        ApplyStateStore.nio(tempRoot)
      )

      val result = service.apply(applyOptions(config).copy(applyParallelism = parallelism(1)))

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(client.maxInFlight == 1)

    test("a member covered by both a file and a directory mapping extracts to both targets"):
      // Regression guard: the single-pass extractor must mark the directory prefix matched even
      // when a file mapping claims the member first, or finish() falsely reports "directory not
      // found"; and the member must land at both mapped targets, as the two-pass planner produced.
      val tempRoot   = tempDirectory("core-overlap")
      val installDir = tempRoot.resolve("alpha")
      val output     = java.io.ByteArrayOutputStream()
      val gzip       = java.util.zip.GZIPOutputStream(output)
      val payload    = "tool-bytes".getBytes(StandardCharsets.UTF_8)
      gzip.write(tarHeader("pkg/tool", payload.length, '0'))
      gzip.write(payload)
      gzip.write(Array.fill[Byte]((512 - (payload.length % 512)) % 512)(0))
      gzip.write(Array.fill[Byte](1024)(0))
      gzip.close()
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(output.toByteArray),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("pkg/tool" -> "bin/alpha"),
        directories = Vector("pkg" -> "share")
      ))

      assert(result.isRight)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "tool-bytes")
      assert(Files.readString(installDir.resolve("share/tool")) == "tool-bytes")

    test("process command executor times out long-running commands"):
      val tempRoot = tempDirectory("core-process-timeout")
      val executor = CommandExecutor.processWithTimeout(Duration.ofMillis(100))

      val result = executor.run(CommandSpec(
        Vector("sh", "-c", "sleep 2"),
        tempRoot,
        Map("PATH" -> sys.env.getOrElse("PATH", "/usr/bin:/bin"))
      ))

      assert(result.left.exists(_.message.contains("timed out")))

    test("process command executor captures stdout and stderr on failure"):
      val tempRoot = tempDirectory("core-process-output")
      val executor = CommandExecutor.processWithTimeout(Duration.ofSeconds(5))

      val result = executor.run(CommandSpec(
        Vector("sh", "-c", "printf 'stdout-line\\n'; printf 'stderr-line\\n' >&2; exit 7"),
        tempRoot,
        Map("PATH" -> sys.env.getOrElse("PATH", "/usr/bin:/bin"))
      ))

      result match
        case Left(error) =>
          assert(error.exitCode.contains(7))
          assert(error.output.stdout.contains("stdout-line"))
          assert(error.output.stderr.contains("stderr-line"))
        case Right(()) => abort("expected command failure")

    test("apply output omits redirected download provenance while state records it"):
      val tempRoot   = tempDirectory("core-download-redirect-state")
      val config     = writeConfig(tempRoot, directBinaryYaml(tempRoot.resolve("alpha")))
      val stateStore = RecordingApplyStateStore(ApplyStateStore.nio(tempRoot))
      val download   = UrlProvenance(
        "https://example.invalid/alpha",
        "https://cdn.example.invalid/alpha",
        Vector(UrlRedirectHop(
          "https://example.invalid/alpha",
          "https://cdn.example.invalid/alpha",
          302
        ))
      )
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(RedirectingBinaryDownloadClient(download), InstallFileSystem.nio),
        stateStore
      )

      val result = service.apply(applyOptions(config).copy(statePath = Some("redirect.state.json")))

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(!result.lines.exists(_.startsWith("download initial url:")))
      assert(!result.lines.exists(_.startsWith("download final url:")))
      assert(!result.lines.exists(_.startsWith("download redirects:")))
      assert(stateStore.savedStates.last.tools.head.download.contains(download))

    test("apply output omits sensitive redirected URLs"):
      val secret   = "secret-token-value"
      val download = UrlProvenance(
        "https://example.invalid/alpha",
        s"https://cdn.example.invalid/$secret/alpha",
        Vector(UrlRedirectHop(
          "https://example.invalid/alpha",
          s"https://cdn.example.invalid/$secret/alpha",
          302
        ))
      )
      val plan = ResolvedPlan(
        ResolvedPolicy.restricted("/tmp/apps"),
        Vector(directTool(Path.of("/tmp/apps/alpha"))),
        SensitiveValueRedactions(Vector(secret))
      )
      val installer = DirectBinaryInstaller(
        RedirectingBinaryDownloadClient(download),
        RecordingInstallFileSystem(stagedFiles = Vector("bin/alpha"))
      )

      val result = installer.installPlan(plan)
      val output = result.lines.mkString("\n")

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(!output.contains(secret))
      assert(!output.contains("download final url:"))
      assert(!output.contains("download redirects:"))

    test("failed replacement restores previous install directory"):
      val tempRoot     = tempDirectory("core-rollback")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val missingStaging = tempRoot.resolve("missing-stage")

      val result = InstallFileSystem.nio.replaceInstall(StagedInstall(missingStaging, installDir))

      assert(result.isLeft)
      assert(Files.readString(existingFile) == "existing")
      assert(!Using.resource(Files.list(tempRoot)): stream =>
        stream.iterator().asScala.exists(_.getFileName.toString.contains(".backup-")))

    test("staging reclaims stale sibling temp dirs but not fresh ones"):
      val tempRoot   = tempDirectory("core-sweep")
      val installDir = tempRoot.resolve("alpha")
      val staleOrphan = Files.createDirectory(tempRoot.resolve(".alpha.stage-stale"))
      Files.writeString(staleOrphan.resolve("leftover"), "x")
      val freshOrphan = Files.createDirectory(tempRoot.resolve(".alpha.backup-fresh"))
      val twoHoursAgo = java.nio.file.attribute.FileTime.fromMillis(
        System.currentTimeMillis() - java.time.Duration.ofHours(2).toMillis
      )
      val _ = Files.setLastModifiedTime(staleOrphan, twoHoursAgo)

      // The artifact lives outside tempRoot so it cannot be mistaken for one of the orphaned
      // staging siblings this test is asserting about — hence its own directory rather than a
      // bare temp file, which would otherwise be the one thing a run still left behind.
      val artifact = tempDirectory("core-sweep-artifact").resolve("alpha.bin")
      Files.writeString(artifact, "alpha")
      val staged = InstallFileSystem.nio.stageDirectBinaryFromFile(
        installDir,
        Vector.empty,
        "bin/alpha",
        artifact
      )

      assert(staged.isRight)
      assert(!Files.exists(staleOrphan))
      assert(Files.exists(freshOrphan))

    test("direct install verifies expected executables"):
      val tempRoot   = tempDirectory("core-direct-missing")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio
      )

      val result = installer.installTool(directTool(
        installDir,
        executables = Vector(
          ResolvedExecutable("bin/alpha", None),
          ResolvedExecutable("bin/missing", None)
        )
      ))

      assert(result == Left(ToolInstallError.MissingExecutable(toolName("alpha"), "bin/missing")))
      assert(!hasStagedInstall(tempRoot, "alpha"))

    test("apply failures end with an actionable suggestion line"):
      // Plan-time strict-policy errors always carry a suggestion; apply-time errors carried one in
      // exactly one of eleven cases, so the failures a user is most likely to hit were the least
      // actionable.
      val rendered = ToolInstallError.render(
        ToolInstallError.MissingExecutable(toolName("alpha"), "bin/alpha"),
        SensitiveValueRedactions.empty
      )

      assert(rendered.contains("verify executable: missing bin/alpha"))
      assert(rendered.contains("suggestion: check spec.plan[].spec.executables[].path"))
