package binstaller.core

import binstaller.config.ChecksumAlgorithm
import binstaller.config.ChecksumSpec
import binstaller.config.ConfigModule
import binstaller.config.ExecutableMode
import binstaller.config.ArchiveExtract
import binstaller.config.ArchiveSpec
import binstaller.config.ArchiveType
import binstaller.config.AllowSudoSymlinks
import binstaller.config.ExtractMapping
import binstaller.config.InstallerShell
import binstaller.config.ValidationError
import binstaller.config.SymlinkPrivilege
import utest.*

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import scala.jdk.CollectionConverters.*
import scala.util.Using

object CoreModuleTest extends TestSuite:

  val tests: Tests = Tests:
    test("module path includes config before core"):
      assert(CoreModule.modulePath == Vector("config", "core"))

    test("pinned versions interpolate into URLs and paths"):
      val plan = resolve(validPinnedYaml)

      val tool = onlyTool(plan)
      assert(tool.installDir == "/home/test/.apps/alpha-1.2.3")
      assert(tool.download.url == "https://example.invalid/alpha-1.2.3-x86_64.tar.gz")
      assert(tool.download.archive.exists(_.files.head.from == "alpha-1.2.3/alpha"))
      assert(tool.symlinks.head.target == "/home/test/.apps/alpha-1.2.3/bin/alpha")

    test("kubectl stable text resolves through a fake HTTP client"):
      val plan = resolve(kubectlResolverYaml, FakeHttpTextClient("v1.33.0"))

      val tool = onlyTool(plan)
      assert(tool.version == ResolvedVersion.Concrete("v1.33.0"))
      assert(tool.download.url == "https://dl.k8s.io/release/v1.33.0/bin/linux/amd64/kubectl")

    test("dynamic latest-url remains dynamic without a concrete version"):
      val plan = resolve(dynamicLatestUrlYaml)

      val tool = onlyTool(plan)
      assert(tool.version == ResolvedVersion.DynamicLatestUrl(Some("upstream latest endpoint")))
      assert(ResolvedVersion.render(tool.version) == "dynamic latest-url")
      assert(tool.download.url == "https://example.invalid/latest/download/beta")

    test("unresolved variables and missing version values produce validation-style errors"):
      val errors = resolveErrors(invalidVariablesYaml)

      assert(errors.exists(errorAt("spec.plan[0].spec.installDir")))
      assert(errors.exists(errorAt("spec.plan[1].spec.download.url")))
      assert(errors.exists(_.message.contains("unresolved variable 'MISSING'")))
      assert(errors.exists(_.message.contains("no concrete version is available")))

    test("shell command substitution is text and is never executed"):
      val plan = resolve(shellSyntaxYaml)

      val tool = onlyTool(plan)
      assert(tool.installDir == "/home/test/.apps/$(echo should-not-run)")
      assert(tool.installer.exists(_.args.head ==
        "/home/test/.apps/$(echo should-not-run)/script.sh"))

    test("example config resolves expected install directories under appsDir"):
      val plan = resolveExampleConfig(FakeHttpTextClient("v1.33.0"))

      assert(plan.policy.appsDir == "/home/test/.apps")
      assert(plan.tools.map(tool => tool.name -> tool.installDir) ==
        exampleToolNames.map(name => name -> s"/home/test/.apps/$name"))
      assert(plan.tools.forall(_.installDir.startsWith(s"${plan.policy.appsDir}/")))

    test("direct binary install writes download bytes to first executable path"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-direct")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes),
        InstallFileSystem.nio
      )

      val result = installer.installTool(directTool(installDir))

      assert(result == Right(ToolInstallSuccess("alpha", installDir.toString)))
      assert(Files.readString(installDir.resolve("bin/alpha")) == "alpha-binary")

    test("sha256 mismatch fails before replacing an existing install"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-checksum")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val tool = directTool(
        installDir,
        checksum = Some(ChecksumSpec(ChecksumAlgorithm.Sha256, "0" * 64))
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
      val fileSystem = RecordingInstallFileSystem()
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha".getBytes),
        fileSystem
      )
      val tool = directTool(
        Path.of("/tmp/alpha"),
        executables = Vector(
          ResolvedExecutable("bin/alpha", Some(ExecutableMode("0700"))),
          ResolvedExecutable("bin/helper", None)
        )
      )

      val result = installer.installTool(tool)

      assert(result == Right(ToolInstallSuccess("alpha", "/tmp/alpha")))
      assert(fileSystem.recordedModes.map(request => request.path -> request.mode.octal) ==
        Vector("bin/alpha" -> "0700", "bin/helper" -> "0755"))
      assert(fileSystem.recordedModes.map(_.mode.numeric) == Vector(448, 493))

    test("download failure preserves existing install and returns a typed error"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-download")
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
            "alpha",
            "https://example.invalid/alpha",
            "network unavailable"
          )
        ))
      assert(Files.readString(existingFile) == "existing")

    test("staging failure preserves existing install and does not replace"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-staging")
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

      assert(result == Left(ToolInstallError.StagingFailed("alpha", "disk full")))
      assert(fileSystem.replaceCalls == 0)
      assert(Files.readString(existingFile) == "existing")

    test("mode application failure preserves existing install and does not replace"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-mode")
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
            "alpha",
            "bin/alpha",
            "0755",
            "permission denied"
          )
        ))
      assert(fileSystem.replaceCalls == 0)
      assert(Files.readString(existingFile) == "existing")

    test("apply renders expected executor failures without throwing"):
      val tempRoot = Files.createTempDirectory("binstaller-core-cli-error")
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
          verboseOutput = VerboseOutput.Disabled,
          applyConfirmation = ApplyConfirmation.Enabled
        )
      )

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("failed alpha: download:")))
      assert(result.lines.exists(_.contains("network unavailable")))
      assert(!result.lines.exists(_.contains("Exception")))
      assert(!result.lines.exists(_.contains("at binstaller.")))

    test("invalid config reports every aggregated validation error concisely"):
      val tempRoot = Files.createTempDirectory("binstaller-core-invalid-config")
      val config   = writeConfig(tempRoot, invalidConfigYaml(tempRoot))
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = service.plan(applyOptions(config))

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.startsWith("apiVersion: unsupported value")))
      assert(result.lines.exists(_.startsWith("kind: unsupported value")))
      assert(
        result.lines.exists(_.startsWith("spec.policy.continueOnError: value must be a boolean"))
      )
      assert(!result.lines.exists(_.contains("ValidationFailed")))
      assert(!result.lines.exists(_.contains("Exception")))

    test("versions output includes pinned resolved and dynamic sources with referencing tools"):
      val service = BinaryInstallerService.resolving(FakeHttpTextClient("v1.34.0"))
      val result  = service.versions(
        applyOptions(exampleConfigPath).copy(applyConfirmation = ApplyConfirmation.Disabled)
      )

      assert(result.exitCode == 0)
      assert(result.lines.exists(_.startsWith("pinned yazi: v26.5.6")))
      assert(result.lines.exists(line =>
        line.startsWith("resolved kubectl: v1.34.0") &&
          line.contains("(tools: kubectl)")
      ))
      assert(result.lines.exists(line =>
        line.startsWith("dynamic minikube: dynamic latest-url") &&
          line.contains("(tools: minikube)")
      ))

    test("non dry-run apply requires yes when policy requireConfirmation is true"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-confirm")
      val installDir = tempRoot.resolve("alpha")
      val config     = writeConfig(tempRoot, directBinaryYaml(installDir))
      val service    = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result =
        service.apply(applyOptions(config).copy(applyConfirmation = ApplyConfirmation.Disabled))

      assert(result.exitCode == 1)
      assert(result.lines == Vector(
        "apply requires confirmation by policy.requireConfirmation; rerun apply with --yes"
      ))
      assert(!Files.exists(installDir))

    test("continueOnError false stops apply after the first failed tool"):
      val tempRoot = Files.createTempDirectory("binstaller-core-stop-on-error")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "stop.state.json"))
      val service  = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Left("network unavailable"),
          "https://example.invalid/beta"  -> Right("beta".getBytes(StandardCharsets.UTF_8))
        ))
      )

      val result = service.apply(applyOptions(config))

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.startsWith("failed alpha: download:")))
      assert(!result.lines.exists(_.contains("installed beta")))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))

    test("continueOnError true continues apply after failed tools"):
      val tempRoot = Files.createTempDirectory("binstaller-core-continue-on-error")
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

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.startsWith("failed alpha: download:")))
      assert(result.lines.exists(_.contains("installed beta")))
      assert(Files.isRegularFile(tempRoot.resolve("apps/beta/bin/beta")))

    test("zip archive file mapping lands at configured relative target path"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-zip")
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

      assert(result == Right(ToolInstallSuccess("alpha", installDir.toString)))
      assert(Files.readString(installDir.resolve("bin/alpha")) == "zip-alpha")

    test("tar.gz archive file mapping lands at configured relative target path"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-targz")
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

      assert(result == Right(ToolInstallSuccess("alpha", installDir.toString)))
      assert(Files.readString(installDir.resolve("bin/alpha")) == "tar-alpha")

    test("tar.gz directory mapping moves extracted root directory into install root"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-targz-dir")
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

      assert(result == Right(ToolInstallSuccess("alpha", installDir.toString)))
      assert(Files.readString(installDir.resolve("bin/alpha")) == "alpha")
      assert(Files.readString(installDir.resolve("share/readme")) == "docs")

    test("archive entries that escape staging are rejected and preserve existing install"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-zip-slip")
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

    test("tar.xz extraction runs through a structured command spec"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-tarxz")
      val installDir      = tempRoot.resolve("zig")
      val commandExecutor = FakeArchiveCommandExecutor("zig-root/bin/zig", "zig")
      val installer       = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("tar-xz-bytes".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarXz,
        directories = Vector("zig-root" -> "."),
        executable = "bin/zig"
      ))

      assert(result == Right(ToolInstallSuccess("alpha", installDir.toString)))
      assert(Files.readString(installDir.resolve("bin/zig")) == "zig")
      assert(commandExecutor.commands.map(_.argv.take(2)) == Vector(Vector("tar", "-xJf")))
      assert(commandExecutor.commands.exists(_.argv.contains("-C")))

    test("installer script runs through configured shell args and explicit env"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-installer")
      val installDir      = tempRoot.resolve("script-tool")
      val scriptPath      = installDir.resolve("install.sh")
      val commandExecutor = InstallingCommandExecutor("bin/script-tool")
      val installer       = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("script-bytes".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor
      )
      val tool = installerTool(
        installDir,
        Some(ResolvedInstaller(
          InstallerShell.Zsh,
          Vector(scriptPath.toString, "--flag"),
          Vector(ResolvedInstallerEnv("CUSTOM", "value")),
          cleanup = true
        ))
      )

      val result = installer.installTool(tool)

      assert(result == Right(ToolInstallSuccess("script-tool", installDir.toString)))
      assert(commandExecutor.commands.map(_.argv) ==
        Vector(Vector("zsh", scriptPath.toString, "--flag")))
      assert(commandExecutor.commands.head.env("CUSTOM") == "value")
      assert(commandExecutor.commands.head.env.keySet == Set("PATH", "CUSTOM"))
      assert(!Files.exists(scriptPath))
      assert(Files.isRegularFile(installDir.resolve("bin/script-tool")))

    test("installer success verifies expected executables"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-installer-missing")
      val installDir      = tempRoot.resolve("script-tool")
      val commandExecutor = RecordingCommandExecutor()
      val installer       = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("script-bytes".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor
      )

      val result = installer.installTool(installerTool(installDir))

      assert(result == Left(ToolInstallError.MissingExecutable("script-tool", "bin/script-tool")))

    test("local symlinks are created under installDir with targets resolved from installDir"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-local-symlink")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio
      )
      val tool = directTool(
        installDir,
        symlinks = Vector(ResolvedSymlink("bin/a", "bin/alpha", SymlinkPrivilege.User))
      )

      val result = installer.installTool(tool)

      assert(result == Right(ToolInstallSuccess("alpha", installDir.toString)))
      assert(Files.isSymbolicLink(installDir.resolve("bin/a")))
      assert(Files.readSymbolicLink(installDir.resolve("bin/a")) ==
        installDir.toAbsolutePath.normalize().resolve("bin/alpha"))

    test("sudo symlink apply requires policy and apply confirmation before writes"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-sudo-gate")
      val installDir      = tempRoot.resolve("alpha")
      val commandExecutor = RecordingCommandExecutor()
      val installer       = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor
      )
      val plan = ResolvedPlan(
        ResolvedPolicy(
          tempRoot.toString,
          None,
          AllowSudoSymlinks.Enabled,
          RequireConfirmation.Disabled,
          ContinueOnError.Disabled
        ),
        Vector(sudoSymlinkTool(installDir))
      )

      val result = installer.installPlan(plan, ApplyConfirmation.Disabled)

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("--yes")))
      assert(commandExecutor.commands.isEmpty)
      assert(!Files.exists(installDir))

    test("sudo symlink apply uses structured argv after policy and confirmation"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-sudo-apply")
      val installDir      = tempRoot.resolve("alpha")
      val commandExecutor = RecordingCommandExecutor()
      val installer       = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor
      )
      val plan = ResolvedPlan(
        ResolvedPolicy(
          tempRoot.toString,
          None,
          AllowSudoSymlinks.Enabled,
          RequireConfirmation.Disabled,
          ContinueOnError.Disabled
        ),
        Vector(sudoSymlinkTool(installDir))
      )

      val result = installer.installPlan(plan, ApplyConfirmation.Enabled)

      assert(result.exitCode == 0)
      assert(commandExecutor.commands.map(_.argv) == Vector(Vector(
        "sudo",
        "ln",
        "-sfn",
        installDir.toAbsolutePath.normalize().resolve("bin/alpha").toString,
        "/usr/local/bin/alpha"
      )))

    test("completed state entries are skipped and failed entries are retried"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-state-resume")
      val config       = writeConfig(tempRoot, twoToolYaml(tempRoot, "resume.state.json"))
      val firstService = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Right("alpha".getBytes(StandardCharsets.UTF_8)),
          "https://example.invalid/beta"  -> Left("network unavailable")
        ))
      )

      val firstResult = firstService.apply(applyOptions(config))

      assert(firstResult.exitCode == 1)
      assert(Files.isRegularFile(tempRoot.resolve("apps/alpha/bin/alpha")))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))

      val secondService = statefulService(tempRoot, RoutingBinaryDownloadClient.success)
      val skippedResult = secondService.apply(
        applyOptions(config).copy(selection = ToolSelection(Vector.empty, Vector("beta")))
      )

      assert(skippedResult.exitCode == 0)
      assert(skippedResult.lines == Vector("skipped alpha: already completed in state"))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))

      val retryResult = secondService.apply(applyOptions(config))
      val state       = loadState(tempRoot, "resume.state.json")

      assert(retryResult.exitCode == 0)
      assert(retryResult.lines.exists(_.contains("skipped alpha")))
      assert(retryResult.lines.exists(_.contains("installed beta")))
      assert(Files.isRegularFile(tempRoot.resolve("apps/beta/bin/beta")))
      assert(state.tools.map(tool => tool.name -> tool.status) ==
        Vector("alpha" -> "completed", "beta" -> "completed"))
      assert(!hasTempStateFile(tempRoot, "resume.state.json"))

    test("incompatible state fails clearly unless reset-state is enabled"):
      val tempRoot  = Files.createTempDirectory("binstaller-core-state-reset")
      val config    = writeConfig(tempRoot, twoToolYaml(tempRoot, "mismatch.state.json"))
      val store     = ApplyStateStore.nio(tempRoot)
      val statePath = tempRoot.resolve("mismatch.state.json")
      store.save(
        statePath,
        ApplyState.empty("other-profile", "other-fingerprint")
      ) match
        case Right(())   => ()
        case Left(error) => abort(s"failed to seed state: $error")
      val service = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val mismatchResult = service.apply(applyOptions(config))
      val resetResult    = service.apply(applyOptions(config).copy(resetState = ResetState.Enabled))

      assert(mismatchResult.exitCode == 1)
      assert(mismatchResult.lines.exists(_.contains("does not match this manifest")))
      assert(mismatchResult.lines.exists(_.contains("--reset-state")))
      assert(resetResult.exitCode == 0)
      assert(loadState(tempRoot, "mismatch.state.json").profileName == "resume-profile")

    test("state paths must be cwd-local filenames"):
      val tempRoot = Files.createTempDirectory("binstaller-core-state-path")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "valid.state.json"))
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val absoluteResult = service.apply(
        applyOptions(config).copy(statePath =
          Some(tempRoot.resolve("absolute.state.json").toString)
        )
      )
      val nestedResult = service.apply(
        applyOptions(config).copy(statePath = Some("nested/state.json"))
      )

      assert(absoluteResult.exitCode == 1)
      assert(absoluteResult.lines.exists(_.contains("absolute state paths are not allowed")))
      assert(nestedResult.exitCode == 1)
      assert(nestedResult.lines.exists(_.contains("current working directory")))
      assert(!Files.exists(tempRoot.resolve("apps")))

    test("state is saved after each terminal tool result"):
      val tempRoot = Files.createTempDirectory("binstaller-core-state-writes")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "writes.state.json"))
      val store    = RecordingApplyStateStore(ApplyStateStore.nio(tempRoot))
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(RoutingBinaryDownloadClient.success, InstallFileSystem.nio),
        store
      )

      val result = service.apply(applyOptions(config))

      assert(result.exitCode == 0)
      assert(store.savedStates.size == 2)
      assert(store.savedStates.map(_.tools.map(tool => tool.name -> tool.status)) ==
        Vector(
          Vector("alpha" -> "completed"),
          Vector("alpha" -> "completed", "beta" -> "completed")
        ))

  private def resolve(
      yaml: String,
      httpTextClient: HttpTextClient = FakeHttpTextClient("")
  ): ResolvedPlan =
    val profile = ConfigModule.loadString(yaml) match
      case Right(value) => value
      case Left(error)  => abort(s"expected valid config, got $error")

    PlanResolver.resolve(profile, testResolutionOptions, httpTextClient) match
      case Right(plan)                                     => plan
      case Left(ResolvePlanError.ValidationFailed(errors)) =>
        abort(s"expected resolved plan, got ${errors.mkString(", ")}")

  private def resolveErrors(yaml: String): Vector[ValidationError] =
    val profile = ConfigModule.loadString(yaml) match
      case Right(value) => value
      case Left(error)  => abort(s"expected config decode success, got $error")

    PlanResolver.resolve(profile, testResolutionOptions, FakeHttpTextClient("")) match
      case Left(ResolvePlanError.ValidationFailed(errors)) => errors
      case Right(plan) => abort(s"expected resolution errors, got $plan")

  private def resolveExampleConfig(httpTextClient: HttpTextClient): ResolvedPlan =
    val profile = ConfigModule.load(exampleConfigPath) match
      case Right(value) => value
      case Left(error)  => abort(s"expected valid example config, got $error")

    PlanResolver.resolve(profile, testResolutionOptions, httpTextClient) match
      case Right(plan)                                     => plan
      case Left(ResolvePlanError.ValidationFailed(errors)) =>
        abort(s"expected resolved example config, got ${errors.mkString(", ")}")

  private def onlyTool(plan: ResolvedPlan): ResolvedTool = plan.tools match
    case Vector(tool) => tool
    case other        => abort(s"expected one tool, got ${other.size}")

  private def errorAt(path: String)(error: ValidationError): Boolean = error.path == path

  private def abort(message: String): Nothing = throw java.lang.AssertionError(message)

  private def statefulService(
      cwd: Path,
      downloadClient: BinaryDownloadClient
  ): BinaryInstallerService = BinaryInstallerService.resolving(
    FakeHttpTextClient(""),
    DirectBinaryInstaller(downloadClient, InstallFileSystem.nio),
    ApplyStateStore.nio(cwd)
  )

  private def applyOptions(config: Path): InstallerOptions = InstallerOptions(
    configPath = config.toString,
    statePath = None,
    resetState = ResetState.Disabled,
    verboseOutput = VerboseOutput.Disabled,
    applyConfirmation = ApplyConfirmation.Enabled
  )

  private def writeConfig(tempRoot: Path, content: String): Path =
    val config = tempRoot.resolve("profile.yaml")
    Files.writeString(config, content)
    config

  private def loadState(tempRoot: Path, name: String): ApplyState =
    ApplyStateStore.nio(tempRoot).load(tempRoot.resolve(name)) match
      case Right(Some(state)) => state
      case other              => abort(s"expected saved state, got $other")

  private def hasTempStateFile(
      tempRoot: Path,
      name: String
  ): Boolean = Using.resource(Files.list(tempRoot)): stream =>
    stream
      .iterator()
      .asScala
      .exists(path => path.getFileName.toString.startsWith(s".$name.tmp-"))

  private def exampleConfigPath: Path = upwardPaths(Path.of("").toAbsolutePath)
    .map(_.resolve("config.example.yaml"))
    .find(Files.exists(_))
    .getOrElse(abort("could not locate config.example.yaml"))

  private def upwardPaths(start: Path): Iterator[Path] =
    Iterator.iterate(start)(_.getParent).takeWhile(_ != null)

  private def directTool(
      installDir: Path,
      checksum: Option[ChecksumSpec] = None,
      executables: Vector[ResolvedExecutable] = Vector(ResolvedExecutable("bin/alpha", None)),
      symlinks: Vector[ResolvedSymlink] = Vector.empty
  ): ResolvedTool = ResolvedTool(
    name = "alpha",
    description = None,
    version = ResolvedVersion.Concrete("1.0.0"),
    installDir = installDir.toString,
    createDirectories = Vector("bin"),
    download = ResolvedDownload(
      url = "https://example.invalid/alpha",
      filename = "alpha",
      checksum = checksum,
      archive = None
    ),
    installer = None,
    executables = executables,
    symlinks = symlinks
  )

  private def sudoSymlinkTool(installDir: Path): ResolvedTool = directTool(
    installDir,
    symlinks = Vector(
      ResolvedSymlink("/usr/local/bin/alpha", "bin/alpha", SymlinkPrivilege.Sudo)
    )
  )

  private def installerTool(
      installDir: Path,
      installer: Option[ResolvedInstaller] = None
  ): ResolvedTool =
    val resolvedInstaller = installer.getOrElse(
      ResolvedInstaller(
        InstallerShell.Sh,
        Vector(installDir.resolve("install.sh").toString),
        Vector.empty,
        cleanup = true
      )
    )
    ResolvedTool(
      name = "script-tool",
      description = None,
      version = ResolvedVersion.Concrete("1.0.0"),
      installDir = installDir.toString,
      createDirectories = Vector("bin"),
      download = ResolvedDownload(
        url = "https://example.invalid/install.sh",
        filename = "install.sh",
        checksum = None,
        archive = None
      ),
      installer = Some(resolvedInstaller),
      executables = Vector(ResolvedExecutable("bin/script-tool", None)),
      symlinks = Vector.empty
    )

  private def archiveTool(
      installDir: Path,
      archiveType: ArchiveType,
      files: Vector[(String, String)] = Vector.empty,
      directories: Vector[(String, String)] = Vector.empty,
      executable: String = "bin/alpha"
  ): ResolvedTool = ResolvedTool(
    name = "alpha",
    description = None,
    version = ResolvedVersion.Concrete("1.0.0"),
    installDir = installDir.toString,
    createDirectories = Vector.empty,
    download = ResolvedDownload(
      url = "https://example.invalid/alpha-archive",
      filename = "alpha-archive",
      checksum = None,
      archive = Some(
        ResolvedArchive(
          ArchiveSpec(
            archiveType,
            ArchiveExtract(
              files.map((from, to) => ExtractMapping(from, to)),
              directories.map((from, to) => ExtractMapping(from, to))
            )
          ),
          files.map((from, to) => ResolvedExtractMapping(from, to)),
          directories.map((from, to) => ResolvedExtractMapping(from, to))
        )
      )
    ),
    installer = None,
    executables = Vector(ResolvedExecutable(executable, None)),
    symlinks = Vector.empty
  )

  private def zipArchive(entries: Vector[(String, String)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    Using.resource(ZipOutputStream(output)): zip =>
      entries.foreach:
        case (name, content) =>
          zip.putNextEntry(ZipEntry(name))
          zip.write(content.getBytes(StandardCharsets.UTF_8))
          zip.closeEntry()
    output.toByteArray

  private def tarGzArchive(entries: Vector[(String, String)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    val gzip   = GZIPOutputStream(output)
    entries.foreach:
      case (name, content) =>
        val bytes = content.getBytes(StandardCharsets.UTF_8)
        gzip.write(tarHeader(name, bytes.length))
        gzip.write(bytes)
        val padding = (512 - (bytes.length % 512)) % 512
        gzip.write(Array.fill[Byte](padding)(0))
    gzip.write(Array.fill[Byte](1024)(0))
    gzip.close()
    output.toByteArray

  private def tarHeader(name: String, size: Int): Array[Byte] =
    val header = Array.fill[Byte](512)(0)
    writeTarField(header, 0, 100, name)
    writeTarField(header, 124, 12, f"$size%011o")
    header(156) = '0'.toByte
    header

  private def writeTarField(header: Array[Byte], offset: Int, length: Int, value: String): Unit =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    Array.copy(bytes, 0, header, offset, math.min(bytes.length, length))

  private def directBinaryYaml(installDir: Path): String =
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: direct-apply
       |spec:
       |  policy:
       |    appsDir: "${installDir.getParent}"
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$installDir"
       |        createDirectories:
       |          - bin
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |""".stripMargin

  private def twoToolYaml(
      tempRoot: Path,
      stateFile: String,
      continueOnError: Boolean = false
  ): String =
    val appsDir = tempRoot.resolve("apps")
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: resume-profile
       |spec:
       |  policy:
       |    appsDir: "$appsDir"
       |    stateFile: "$stateFile"
       |    continueOnError: $continueOnError
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |    beta: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$appsDir/alpha"
       |        createDirectories:
       |          - bin
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |    - name: beta
       |      kind: binary-tool
       |      spec:
       |        versionRef: beta
       |        installDir: "$appsDir/beta"
       |        createDirectories:
       |          - bin
       |        download:
       |          url: https://example.invalid/beta
       |          filename: beta
       |        executables:
       |          - path: bin/beta
       |""".stripMargin

  private def invalidConfigYaml(tempRoot: Path): String =
    s"""
       |apiVersion: wrong.example/v1
       |kind: WrongKind
       |metadata:
       |  name: invalid
       |spec:
       |  policy:
       |    appsDir: "${tempRoot.resolve("apps")}"
       |    continueOnError: no
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "${tempRoot.resolve("apps/alpha")}"
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |""".stripMargin

  private val testResolutionOptions: ResolutionOptions = ResolutionOptions(
    Map("HOME" -> "/home/test")
  )

  private val exampleToolNames: Vector[String] = Vector(
    "yazi",
    "zig",
    "minikube",
    "xplr",
    "kind",
    "zellij",
    "helm",
    "kubectl",
    "kustomize",
    "neovide",
    "neovim",
    "lazygit",
    "jujutsu",
    "dotbot",
    "nerd-font-installer"
  )

  private val validPinnedYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: pinned
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |    allowSudoSymlinks: true
      |  vars:
      |    linuxArch: x86_64
      |  versions:
      |    alpha: "1.2.3"
      |  plan:
      |    - name: alpha
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${appsDir}/alpha-${version}"
      |        download:
      |          url: "https://example.invalid/alpha-${version}-${linuxArch}.tar.gz"
      |          filename: "alpha-${version}.tar.gz"
      |          archive:
      |            type: tar.gz
      |            extract:
      |              files:
      |                - from: "alpha-${version}/alpha"
      |                  to: bin/alpha
      |        executables:
      |          - path: bin/alpha
      |        symlinks:
      |          - path: bin/a
      |            target: "${installDir}/bin/alpha"
      |""".stripMargin

  private val kubectlResolverYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: kubectl
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |  vars: {}
      |  versions:
      |    kubectl:
      |      resolver:
      |        type: http-text
      |        url: https://dl.k8s.io/release/stable.txt
      |  plan:
      |    - name: kubectl
      |      kind: binary-tool
      |      spec:
      |        versionRef: kubectl
      |        installDir: "${appsDir}/kubectl"
      |        download:
      |          url: "https://dl.k8s.io/release/${version}/bin/linux/amd64/kubectl"
      |          filename: kubectl
      |        executables:
      |          - path: bin/kubectl
      |""".stripMargin

  private val dynamicLatestUrlYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: dynamic
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |  vars: {}
      |  versions:
      |    beta:
      |      dynamic:
      |        type: latest-url
      |        note: upstream latest endpoint
      |  plan:
      |    - name: beta
      |      kind: binary-tool
      |      spec:
      |        versionRef: beta
      |        installDir: "${appsDir}/beta"
      |        download:
      |          url: https://example.invalid/latest/download/beta
      |          filename: beta
      |        executables:
      |          - path: bin/beta
      |""".stripMargin

  private val invalidVariablesYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: invalid-vars
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |  vars: {}
      |  versions:
      |    alpha: "1.0.0"
      |    beta:
      |      dynamic:
      |        type: latest-url
      |  plan:
      |    - name: alpha
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${appsDir}/${MISSING}"
      |        download:
      |          url: https://example.invalid/alpha
      |          filename: alpha
      |        executables:
      |          - path: bin/alpha
      |    - name: beta
      |      kind: binary-tool
      |      spec:
      |        versionRef: beta
      |        installDir: "${appsDir}/beta"
      |        download:
      |          url: "https://example.invalid/releases/${version}/beta"
      |          filename: beta
      |        executables:
      |          - path: bin/beta
      |""".stripMargin

  private val shellSyntaxYaml: String = """
                                          |apiVersion: binstaller.io/v1alpha1
                                          |kind: BinaryDistributionProfile
                                          |metadata:
                                          |  name: shell-text
                                          |spec:
                                          |  policy:
                                          |    appsDir: "${HOME}/.apps"
                                          |  vars:
                                          |    shellText: "$(echo should-not-run)"
                                          |  versions:
                                          |    script: "1.0.0"
                                          |  plan:
                                          |    - name: script
                                          |      kind: binary-tool
                                          |      spec:
                                          |        versionRef: script
                                          |        installDir: "${appsDir}/${shellText}"
                                          |        createDirectories:
                                          |          - bin
                                          |        download:
                                          |          url: https://example.invalid/script.sh
                                          |          filename: script.sh
                                          |        installer:
                                          |          shell: sh
                                          |          args:
                                          |            - "${downloadPath}"
                                          |          cleanup: true
                                          |        executables:
                                          |          - path: bin/script
                                          |""".stripMargin

private final class FakeHttpTextClient(text: String) extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] =
    if url == "https://dl.k8s.io/release/stable.txt" then Right(text)
    else Left(HttpTextError(url, s"unexpected URL $url"))

private final class FakeBinaryDownloadClient(result: Either[BinaryDownloadError, Array[Byte]])
    extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] =
    result.left.map(error => error.copy(url = url))

private object FakeBinaryDownloadClient:

  def success(bytes: Array[Byte]): FakeBinaryDownloadClient = FakeBinaryDownloadClient(Right(bytes))

  def failure(message: String): FakeBinaryDownloadClient =
    FakeBinaryDownloadClient(Left(BinaryDownloadError("", message)))

private final class RoutingBinaryDownloadClient(
    results: Map[String, Either[String, Array[Byte]]]
) extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] = results
    .getOrElse(url, Left(s"unexpected URL $url"))
    .left
    .map(message => BinaryDownloadError(url, message))

private object RoutingBinaryDownloadClient:

  def success: RoutingBinaryDownloadClient = RoutingBinaryDownloadClient(Map(
    "https://example.invalid/alpha" -> Right("alpha".getBytes(StandardCharsets.UTF_8)),
    "https://example.invalid/beta"  -> Right("beta".getBytes(StandardCharsets.UTF_8))
  ))

private final class RecordingApplyStateStore(delegate: ApplyStateStore) extends ApplyStateStore:

  private var states: Vector[ApplyState] = Vector.empty

  def savedStates: Vector[ApplyState] = states

  def cwd: Path = delegate.cwd

  def load(path: Path): Either[ApplyStateError, Option[ApplyState]] = delegate.load(path)

  def save(path: Path, state: ApplyState): Either[ApplyStateError, Unit] =
    states = states :+ state
    delegate.save(path, state)

private final class FakeArchiveCommandExecutor(path: String, content: String)
    extends CommandExecutor:

  private var recordedCommands: Vector[CommandSpec] = Vector.empty

  def commands: Vector[CommandSpec] = recordedCommands

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] =
    recordedCommands = recordedCommands :+ spec
    val extractDir = spec.argv.dropWhile(_ != "-C").drop(1).headOption.map(Path.of(_))
    extractDir match
      case Some(directory) =>
        val target = directory.resolve(path)
        Files.createDirectories(target.getParent)
        Files.writeString(target, content)
        Right(())
      case None => Left(CommandExecutionError(spec, "missing -C extraction directory", None))

private final class RecordingCommandExecutor(result: Either[String, Unit] = Right(()))
    extends CommandExecutor:

  private var recordedCommands: Vector[CommandSpec] = Vector.empty

  def commands: Vector[CommandSpec] = recordedCommands

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] =
    recordedCommands = recordedCommands :+ spec
    result.left.map(message => CommandExecutionError(spec, message, None))

private final class InstallingCommandExecutor(executablePath: String) extends CommandExecutor:

  private var recordedCommands: Vector[CommandSpec] = Vector.empty

  def commands: Vector[CommandSpec] = recordedCommands

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] =
    recordedCommands = recordedCommands :+ spec
    val target = spec.cwd.resolve(executablePath)
    Files.createDirectories(target.getParent)
    Files.writeString(target, "installed")
    Right(())

private final class RecordingInstallFileSystem(
    stageFailure: Option[String] = None,
    modeFailure: Option[String] = None
) extends InstallFileSystem:

  private var modes: Vector[ExecutableModeRequest] = Vector.empty
  private var replacements: Int                    = 0

  def recordedModes: Vector[ExecutableModeRequest] = modes

  def replaceCalls: Int = replacements

  def stageDirectBinary(
      installDir: Path,
      createDirectories: Vector[String],
      executablePath: String,
      bytes: Array[Byte]
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] = stageFailure match
    case Some(message) => Left(InstallFileSystemError.StagingFailed(message))
    case None          => Right(StagedInstall(Path.of("/tmp/staged-alpha"), installDir))

  def stageArchive(
      installDir: Path,
      createDirectories: Vector[String],
      archive: ResolvedArchive,
      bytes: Array[Byte],
      commandExecutor: CommandExecutor
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] = stageFailure match
    case Some(message) => Left(InstallFileSystemError.StagingFailed(message))
    case None          => Right(StagedInstall(Path.of("/tmp/staged-alpha"), installDir))

  def applyExecutableModes(
      stagedInstall: StagedInstall,
      executables: Vector[ExecutableModeRequest]
  ): Either[InstallFileSystemError.ModeApplicationFailed, Unit] =
    modes = executables
    modeFailure match
      case Some(message) =>
        val first = executables.head
        Left(
          InstallFileSystemError.ModeApplicationFailed(
            first.path,
            first.mode.octal,
            message
          )
        )
      case None => Right(())

  def replaceInstall(
      stagedInstall: StagedInstall
  ): Either[InstallFileSystemError.ReplacementFailed, Unit] =
    replacements = replacements + 1
    modes.foreach: mode =>
      val target = stagedInstall.installDir.resolve(mode.path)
      Files.createDirectories(target.getParent)
      Files.writeString(target, "installed")
    Right(())

  def discardStaged(stagedInstall: StagedInstall): Unit = ()
