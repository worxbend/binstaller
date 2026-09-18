package binstaller.cli

import binstaller.config.Sha256Digest
import binstaller.config.ToolName
import binstaller.core.ApplyParallelism
import binstaller.core.BinaryInstallerService
import binstaller.core.BinaryDownloadArtifact
import binstaller.core.BinaryDownloadClient
import binstaller.core.BinaryDownloadError
import binstaller.core.BinaryDownloadProgress
import binstaller.core.BinaryDownloadProgressObserver
import binstaller.core.DirectBinaryInstaller
import binstaller.core.DownloadProgressStatus
import binstaller.core.HttpTextClient
import binstaller.core.HttpTextError
import binstaller.core.HttpTextResponse
import binstaller.core.HostPlatform
import binstaller.core.InstallFileSystem
import binstaller.core.InstallerEventObserver
import binstaller.core.InstallerEvent
import binstaller.core.InstallerOptions
import binstaller.core.InstallerResult
import binstaller.core.NewerVersionStatus
import binstaller.core.InstallerRunStatus
import binstaller.core.VersionSummaryRow
import binstaller.core.LockedApplyMode
import binstaller.core.LockOptions
import binstaller.core.ResetState
import binstaller.core.ResolutionOptions
import binstaller.core.ToolSelection
import binstaller.core.ApplyStateStore
import binstaller.core.UrlProvenance
import binstaller.core.UrlRedirectHop
import utest.*

import scala.jdk.CollectionConverters.*
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object CliModuleTest extends TestSuite:

  // Same leak as the core suite had: nothing deleted the temp directories these tests create, so a
  // run left them behind permanently. `CoreTestSupport`'s registry is `private[core]`, so the CLI
  // suite keeps its own rather than widening that.
  private val createdTempDirectories =
    java.util.concurrent.ConcurrentLinkedQueue[java.nio.file.Path]()

  private def tempDirectory(name: String): java.nio.file.Path =
    val directory = Files.createTempDirectory(s"binstaller-$name-")
    val _         = createdTempDirectories.add(directory)
    directory

  override def utestAfterAll(): Unit =
    createdTempDirectories.forEach(deleteRecursively)
    createdTempDirectories.clear()

  private def deleteRecursively(path: java.nio.file.Path): Unit = if Files.exists(path) then
    scala.util.Using.resource(Files.walk(path)): stream =>
      stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach: child =>
        val _ = scala.util.Try(Files.deleteIfExists(child))

  val tests: Tests = Tests:
    test("terminal password conversion copies and clears the mutable input buffer"):
      val chars  = "secret".toCharArray
      val result = TerminalSudoCredentialProvider.passwordFromChars(Some(chars))

      assert(result.isRight)
      assert(chars.forall(_ == '\u0000'))

    test("terminal password conversion treats an empty or missing password as canceled"):
      assert(TerminalSudoCredentialProvider.passwordFromChars(Some(Array.emptyCharArray)) ==
        Left(binstaller.core.SudoCredentialError.Canceled))
      assert(TerminalSudoCredentialProvider.passwordFromChars(None) ==
        Left(binstaller.core.SudoCredentialError.Canceled))

    test("help describes the binstaller binary installer"):
      val result = runCli(Vector("--help"))

      assert(result.exitCode == 0)
      assert(result.out.contains("binstaller"))
      assert(result.out.contains("binary installer"))

    test("help renders a styled binstaller logo"):
      val result = runCli(Vector("--help"))
      val plain  = stripAnsi(result.out)

      assert(result.exitCode == 0)
      assert(plain.contains("| |__ (_)_ __  ___| |_ __ _| | | ___ _ __"))
      assert(plain.contains("binary installer"))
      assert(result.out.contains("\u001b["))

    test("help lists supported commands"):
      val result = runCli(Vector("--help"))

      assert(result.out.contains("plan"))
      assert(result.out.contains("apply"))
      assert(result.out.contains("versions"))
      assert(result.out.contains("lock"))

    test("help omits out-of-scope first-class commands"):
      val result = runCli(Vector("--help"))

      assert(!result.out.contains("apt"))
      assert(!result.out.contains("dotfiles"))
      assert(!result.out.contains("Nerd Fonts"))

    test("plan help describes non-mutating output"):
      val result = runCli(Vector("plan", "--help"))

      assert(result.exitCode == 0)
      assert(result.out.contains("Render the binary installer plan without changing files."))
      assert(result.out.contains("Default: cwd config.yaml."))

    test("apply help describes apply output"):
      val result = runCli(Vector("apply", "--help"))

      assert(result.exitCode == 0)
      assert(result.out.contains("Apply the binary installer plan."))

    test("plan uses config.yaml from cwd by default"):
      val service = RecordingInstallerService()
      val result  = runCli(Vector("plan"), service)

      assert(result.exitCode == 0)
      assert(service.planOptions.exists(_.configPath == defaultConfigPath))

    test("apply uses config.yaml from cwd by default"):
      val service = RecordingInstallerService()
      val result  = runCli(Vector("apply"), service)

      assert(result.exitCode == 0)
      assert(service.applyOptions.exists(_.configPath == defaultConfigPath))

    test("versions uses config.yaml from cwd by default"):
      val service = RecordingInstallerService()
      val result  = runCli(Vector("versions"), service)

      assert(result.exitCode == 0)
      assert(service.versionsOptions.exists(_.configPath == defaultConfigPath))

    test("lock uses config.yaml from cwd by default"):
      val service = RecordingInstallerService()
      val result  = runCli(Vector("lock"), service)

      assert(result.exitCode == 0)
      assert(service.lockInstallerOptions.exists(_.configPath == defaultConfigPath))

    test("command hierarchy forwards the options owned by each command level"):
      val selectedOptions = InstallerOptions(
        configPath = "profile.yaml",
        statePath = None,
        resetState = ResetState.Disabled,
        verboseOutput = binstaller.core.VerboseOutput.Disabled,
        selection = ToolSelection(Vector("alpha"), Vector("beta"))
      )
      val commonArgs = Vector(
        "--config",
        "profile.yaml",
        "--only",
        "alpha",
        "--skip",
        "beta"
      )
      val lockedOptions = selectedOptions.copy(
        lockPath = "custom.lock.json",
        lockedApply = LockedApplyMode.Enabled
      )
      val cases = Vector(
        CommandOptionsCase(
          Vector("plan") ++ commonArgs ++ Vector("--locked", "--lock-file", "custom.lock.json"),
          _.planOptions,
          lockedOptions
        ),
        CommandOptionsCase(
          Vector("apply") ++ commonArgs ++ Vector(
            "--locked",
            "--lock-file",
            "custom.lock.json",
            "--parallelism",
            "8"
          ),
          _.applyOptions,
          lockedOptions.copy(applyParallelism = positiveParallelism(8))
        ),
        CommandOptionsCase(Vector("versions") ++ commonArgs, _.versionsOptions, selectedOptions),
        CommandOptionsCase(Vector("lock") ++ commonArgs, _.lockInstallerOptions, selectedOptions)
      )

      cases.foreach: commandCase =>
        val service = RecordingInstallerService()
        val result  = runCli(commandCase.args, service)

        assert(result.exitCode == 0)
        assert(commandCase.recordedOptions(service).contains(commandCase.expectedOptions))

    test("apply forwards state override and reset-state"):
      val service = RecordingInstallerService()
      val result  = runCli(
        Vector(
          "apply",
          "--config",
          "profile.yaml",
          "--state",
          "custom.state.json",
          "--reset-state"
        ),
        service
      )

      assert(result.exitCode == 0)
      assert(service.applyOptions.exists(_.statePath.contains("custom.state.json")))
      assert(service.applyOptions.exists(_.resetState == ResetState.Enabled))

    test("apply rejects a non-positive parallelism with a usage error, not a stack trace"):
      val service = RecordingInstallerService()
      val result  = runCli(Vector("apply", "--parallelism", "0"), service)

      assert(result.exitCode == 2)
      // The complaint belongs on stderr: apply's stdout is meant to be pipeable, and a usage
      // message mixed into that stream corrupts whatever is consuming it.
      assert(result.err.contains("--parallelism must be at least 1, got 0"))
      assert(!result.out.contains("must be at least 1"))
      assert(service.applyOptions.isEmpty)

    test("output style honors NO_COLOR, TERM=dumb, and force-color escape hatches"):
      import CliOutputStyle.{Ansi, Plain}
      assert(CliOutputStyle.forProcessOutput(Map.empty, interactive = true) == Ansi)
      assert(CliOutputStyle.forProcessOutput(Map.empty, interactive = false) == Plain)
      assert(CliOutputStyle.forProcessOutput(Map("NO_COLOR" -> ""), interactive = true) == Plain)
      assert(CliOutputStyle.forProcessOutput(Map("TERM" -> "dumb"), interactive = true) == Plain)
      // stdin redirected (non-interactive) but the user forces color:
      assert(CliOutputStyle.forProcessOutput(Map("FORCE_COLOR" -> "1"), interactive = false) ==
        Ansi)
      assert(
        CliOutputStyle.forProcessOutput(Map("CLICOLOR_FORCE" -> "1"), interactive = false) == Ansi
      )
      assert(CliOutputStyle.forProcessOutput(Map("FORCE_COLOR" -> "0"), interactive = false) ==
        Plain)
      // NO_COLOR wins even against a force request:
      assert(
        CliOutputStyle.forProcessOutput(
          Map("NO_COLOR" -> "", "FORCE_COLOR" -> "1"),
          interactive = true
        ) == Plain
      )

    test("lock forwards lock file path"):
      val service = RecordingInstallerService()
      val result  = runCli(
        Vector(
          "lock",
          "--config",
          "profile.yaml",
          "--lock-file",
          "custom.lock.json"
        ),
        service
      )

      assert(result.exitCode == 0)
      assert(result.out.contains("lock"))
      assert(service.lockOptions.exists(_.outputPath == "custom.lock.json"))

    test("plan prints all example tools in manifest order"):
      val result = runCli(
        Vector("plan", "--config", configExamplePath.toString),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(renderedToolNames(result.out) == exampleToolNames)

    test("plan only selection prints one requested tool"):
      val result = runCli(
        Vector("plan", "--config", configExamplePath.toString, "--only", "yazi"),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(result.out.contains("tools: 1"))
      assert(renderedToolNames(result.out) == Vector("yazi"))

    test("plan only selection accepts a comma-separated list in a single flag"):
      val result = runCli(
        Vector("plan", "--config", configExamplePath.toString, "--only", "yazi,lazygit"),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(result.out.contains("tools: 2"))
      assert(renderedToolNames(result.out).toSet == Set("yazi", "lazygit"))

    test("plan only selection accepts a space-separated list in a single flag"):
      val result = runCli(
        Vector("plan", "--config", configExamplePath.toString, "--only", "yazi", "lazygit"),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(result.out.contains("tools: 2"))
      assert(renderedToolNames(result.out).toSet == Set("yazi", "lazygit"))

    test("plan only selection combines comma-separated values across repeated flags"):
      val result = runCli(
        Vector(
          "plan",
          "--config",
          configExamplePath.toString,
          "--only",
          "yazi,lazygit",
          "--only",
          "neovim"
        ),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(result.out.contains("tools: 3"))
      assert(renderedToolNames(result.out).toSet == Set("yazi", "lazygit", "neovim"))

    test("plan skip selection omits the requested tool and preserves order"):
      val result = runCli(
        Vector("plan", "--config", configExamplePath.toString, "--skip", "neovim"),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(renderedToolNames(result.out) == exampleToolNames.filterNot(_ == "neovim"))

    test("plan renders every sudo symlink command and marks sudo risk"):
      val result = runCli(
        Vector("plan", "--config", configExamplePath.toString, "--only", "neovim"),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(result.out.contains("sudo risk: YES"))
      assert(result.out.linesIterator.count(_.contains("sudo ln -sfn")) == 5)

    test("versions output shows package summary without URL provenance"):
      val service = BinaryInstallerService.resolving(
        RedirectingHttpTextClient(
          "v1.34.0",
          UrlProvenance(
            "https://dl.k8s.io/release/stable.txt",
            "https://cdn.example.invalid/kubernetes/stable.txt",
            Vector(UrlRedirectHop(
              "https://dl.k8s.io/release/stable.txt",
              "https://cdn.example.invalid/kubernetes/stable.txt",
              302
            ))
          )
        ),
        exampleResolutionOptions
      )

      val result = runCli(Vector("versions", "--config", configExamplePath.toString), service)

      assert(result.exitCode == 0)
      assert(result.out.contains("\u001b["))
      val plainOutput = stripAnsi(result.out)
      assert(plainOutput.contains("package"))
      assert(plainOutput.contains("newer version"))
      assert(plainOutput.contains("kubectl"))
      assert(plainOutput.contains("v1.34.0"))
      assert(!plainOutput.contains("https://dl.k8s.io/release/stable.txt"))
      assert(!plainOutput.contains("https://cdn.example.invalid/kubernetes/stable.txt"))
      assert(!plainOutput.contains("final url"))

    test("plain versions output preserves every core-authored line"):
      // Plain output adds no styling, so rebuilding its table from structured rows can only lose
      // information, including successful diagnostics that core appends after the table.
      val awkward = VersionSummaryRow("alpha", "1.0  beta", NewerVersionStatus.UpToDate)
      val result  = InstallerResult(
        Vector(
          "binstaller versions",
          "package  version    newer version",
          "alpha    1.0  beta  -",
          "latest-version lookup completed with one recoverable diagnostic"
        ),
        InstallerRunStatus.Succeeded,
        versionRows = Vector(awkward)
      )

      assert(CliVersionsOutput.colorLines(result, CliOutputStyle.Plain) == result.lines)

    test("styled versions preserve spaces within a version value"):
      val result = InstallerResult(
        Vector("binstaller versions"),
        InstallerRunStatus.Succeeded,
        versionRows = Vector(VersionSummaryRow("alpha", "1.0  beta", NewerVersionStatus.UpToDate))
      )
      val styled = CliVersionsOutput.colorLines(result, CliOutputStyle.Ansi).mkString("\n")
      val plain  = stripAnsi(styled)

      assert(styled.contains("\u001b["))
      assert(plain.contains("1.0  beta"))
      assert(plain.linesIterator.exists(line => line.startsWith("alpha") && line.endsWith("-")))

    test("versions honours --only and reports an unknown tool"):
      // `versions` used to resolve the whole manifest and ignore the selection flags, so a caller
      // passing --only got every tool anyway, with no indication the flag had been dropped.
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient("v1.34.0"),
        exampleResolutionOptions
      )

      val selected = runCli(
        Vector("versions", "--config", configExamplePath.toString, "--only", "kubectl"),
        service
      )

      assert(selected.exitCode == 0)
      val plainSelected = stripAnsi(selected.out)
      assert(plainSelected.contains("kubectl"))
      assert(!plainSelected.contains("helm"))

      val unknown = runCli(
        Vector("versions", "--config", configExamplePath.toString, "--only", "nope"),
        service
      )

      assert(unknown.exitCode == 1)
      assert(unknown.out.contains("unknown tool 'nope'"))

    test("plan renders local and sudo symlink actions without executing them"):
      val tempRoot = tempDirectory("cli-dry-symlinks")
      val appsDir  = tempRoot.resolve("apps")
      val config   = writeConfig(tempRoot, noWriteYaml(appsDir, "state.json"))

      val result = runCli(
        Vector("plan", "--config", config.toString),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(result.out.contains("[local] ln -sfn"))
      assert(result.out.contains("[sudo risk] sudo ln -sfn"))
      assert(!Files.exists(appsDir))

    test("plan does not create install or state paths"):
      val tempRoot  = tempDirectory("cli-test")
      val appsDir   = tempRoot.resolve("apps")
      val stateFile = tempRoot.resolve("state.json")
      val config    = writeConfig(tempRoot, noWriteYaml(appsDir, stateFile.getFileName.toString))
      val service   = resolvingServiceWithStateRoot(tempRoot)

      val planResult = runCli(Vector("plan", "--config", config.toString), service)

      assert(planResult.exitCode == 0)
      assert(!Files.exists(appsDir))
      assert(!Files.exists(stateFile))
      assert(!Files.exists(appsDir.resolve("alpha")))

    test("apply renders download progress bar in place"):
      val tempRoot = tempDirectory("cli-progress")
      val appsDir  = tempRoot.resolve("apps")
      val config   = writeConfig(tempRoot, progressYaml(appsDir))
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient("v1.34.0"),
        DirectBinaryInstaller(
          ProgressBinaryDownloadClient("alpha-binary".getBytes),
          InstallFileSystem.nio
        )
      )

      val result = runCli(
        Vector("apply", "--config", config.toString),
        service
      )

      assert(result.exitCode == 0)
      val plainOutput = stripAnsi(result.out)
      assert(plainOutput.contains("\r⬇ downloading alpha"))
      assert(plainOutput.contains("[███████████████░░░░░░░░░░░░░░░] 50%"))
      assert(plainOutput.contains("\r✅ completed alpha [██████████████████████████████] 100%"))
      assert(!plainOutput.contains("\n⬇ downloading alpha"))
      assert(plainOutput.contains("installed alpha"))
      assert(plainOutput.contains("✨ Summary"))
      assert(plainOutput.contains("✅ installed: 1"))
      assert(plainOutput.contains("🎉 apply completed successfully"))
      assert(result.out.contains("\u001b["))
      assert(Files.readString(appsDir.resolve("alpha/bin/alpha")) == "alpha-binary")

    test("apply plain output omits ANSI and cursor controls"):
      val tempRoot = tempDirectory("cli-progress-plain")
      val appsDir  = tempRoot.resolve("apps")
      val config   = writeConfig(tempRoot, progressYaml(appsDir))
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient("v1.34.0"),
        DirectBinaryInstaller(
          ProgressBinaryDownloadClient("alpha-binary".getBytes),
          InstallFileSystem.nio
        )
      )

      val result = runCli(
        Vector("apply", "--config", config.toString),
        service,
        CliOutputStyle.Plain
      )

      assert(result.exitCode == 0)
      assert(!result.out.contains("\u001b["))
      assert(!result.out.contains("\r"))
      assert(result.out.contains("✅ completed alpha [██████████████████████████████] 100%"))
      assert(result.out.contains("installed alpha"))
      assert(result.out.contains("✨ Summary"))

    test("apply clears an active progress row before Picocli reports an exception"):
      val sharedBuffer = StringWriter()
      val out          = PrintWriter(sharedBuffer, true)
      val err          = PrintWriter(sharedBuffer, true)
      val exitCode     = CliModule
        .commandLine(ExceptionalApplyInstallerService, out, err, CliOutputStyle.Ansi)
        .execute("apply")
      val output       = sharedBuffer.toString
      val cleanupIndex = output.indexOf("\r\u001b[K")
      val errorIndex   = output.indexOf(ExceptionalApplyInstallerService.message)

      assert(exitCode != 0)
      assert(cleanupIndex >= 0)
      assert(errorIndex > cleanupIndex)

    test("apply colours result lines by their status, not by their wording"):
      // The colour must come from the typed status core pairs with each rendered line. A prefix
      // test on the text would keep passing here while silently losing its colour the moment core
      // reworded "installed " or "failed ".
      val tempRoot = tempDirectory("cli-result-colour")
      val appsDir  = tempRoot.resolve("apps")
      val config   = writeConfig(tempRoot, progressYaml(appsDir))
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient("v1.34.0"),
        DirectBinaryInstaller(
          ProgressBinaryDownloadClient("alpha-binary".getBytes),
          InstallFileSystem.nio
        )
      )

      val result = runCli(Vector("apply", "--config", config.toString), service)

      assert(result.exitCode == 0)
      // The green escape must sit immediately before the text, i.e. the line itself is coloured.
      val greenPrefix = fansi.Color.Green("x").toString.takeWhile(_ != 'x')
      assert(result.out.contains(s"${greenPrefix}installed alpha"))

    test("apply renders overlapping downloads as separate progress lines"):
      val tempRoot = tempDirectory("cli-parallel-progress")
      val appsDir  = tempRoot.resolve("apps")
      val config   = writeConfig(tempRoot, parallelProgressYaml(appsDir))
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient("v1.34.0"),
        DirectBinaryInstaller(
          ConcurrentProgressBinaryDownloadClient(Map(
            "https://example.invalid/alpha" -> "alpha-binary".getBytes,
            "https://example.invalid/beta"  -> "beta-binary".getBytes
          )),
          InstallFileSystem.nio
        )
      )

      val result = runCli(
        Vector("apply", "--config", config.toString, "--parallelism", "2"),
        service
      )

      assert(result.exitCode == 0)
      val plainOutput = stripAnsi(result.out)
      assert(plainOutput.contains("⬇ downloading alpha"))
      assert(plainOutput.contains("⬇ downloading beta"))
      assert(plainOutput.contains("✅ completed alpha [██████████████████████████████] 100%"))
      assert(plainOutput.contains("✅ completed beta [██████████████████████████████] 100%"))
      assert(result.out.contains("\u001b[2A"))
      assert(result.out.contains("\u001b[2K"))
      assert(!plainOutput.contains("https://example.invalid"))
      assert(plainOutput.contains("installed alpha"))
      assert(plainOutput.contains("installed beta"))
      assert(plainOutput.contains("✅ installed: 2"))

  private def runCli(
      args: Vector[String],
      service: BinaryInstallerService = StubBinaryInstallerService,
      outputStyle: CliOutputStyle = CliOutputStyle.Ansi
  ): CliRunResult =
    val outBuffer = StringWriter()
    val errBuffer = StringWriter()
    val out       = PrintWriter(outBuffer, true)
    val err       = PrintWriter(errBuffer, true)
    val exitCode  = CliModule.commandLine(service, out, err, outputStyle).execute(args*)
    CliRunResult(exitCode, outBuffer.toString, errBuffer.toString)

  private def renderedToolNames(output: String): Vector[String] =
    stripAnsi(output).linesIterator.toVector.collect:
      case ToolHeading(name) => name

  private def stripAnsi(output: String): String = output.replaceAll("\u001b\\[[;\\d]*m", "")

  private def defaultConfigPath: String = Path.of("config.yaml").toAbsolutePath.normalize().toString

  private def positiveParallelism(value: Int): ApplyParallelism =
    ApplyParallelism.fromInt(value) match
      case Right(parallelism) => parallelism
      case Left(error) => throw java.lang.AssertionError(s"unexpected parallelism error: $error")

  private def writeConfig(tempRoot: Path, content: String): Path =
    val path = tempRoot.resolve("profile.yaml")
    Files.writeString(path, content)
    path

  private def noWriteYaml(appsDir: Path, stateFile: String): String =
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: no-writes
       |spec:
       |  policy:
       |    appsDir: "$appsDir"
       |    stateFile: "$stateFile"
       |    allowSudoSymlinks: true
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$appsDir/alpha"
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |        symlinks:
       |          - path: bin/a
       |            target: bin/alpha
       |          - path: /usr/local/bin/alpha
       |            target: "$appsDir/alpha/bin/alpha"
       |            sudo: true
       |""".stripMargin

  private def progressYaml(appsDir: Path): String = s"""
                                                       |apiVersion: binstaller.io/v1alpha1
                                                       |kind: BinaryDistributionProfile
                                                       |metadata:
                                                       |  name: progress
                                                       |spec:
                                                       |  policy:
                                                       |    appsDir: "$appsDir"
                                                       |  vars: {}
                                                       |  versions:
                                                       |    alpha: "1.0.0"
                                                       |  plan:
                                                       |    - name: alpha
                                                       |      kind: binary-tool
                                                       |      spec:
                                                       |        versionRef: alpha
                                                       |        installDir: "$appsDir/alpha"
                                                       |        download:
                                                       |          url: https://example.invalid/alpha
                                                       |          filename: alpha
                                                       |        executables:
                                                       |          - path: bin/alpha
                                                       |""".stripMargin

  private def parallelProgressYaml(appsDir: Path): String =
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: parallel-progress
       |spec:
       |  policy:
       |    appsDir: "$appsDir"
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
       |        download:
       |          url: https://example.invalid/beta
       |          filename: beta
       |        executables:
       |          - path: bin/beta
       |""".stripMargin

  private def findRepoFile(name: String): Path = repoRootCandidates
    .map(_.resolve(name))
    .find(Files.isRegularFile(_))
    .getOrElse(
      throw java.lang.AssertionError(s"could not find $name from ${Path.of("").toAbsolutePath}")
    )

  private def repoRootCandidates: Iterator[Path] =
    sys.props.get("binstaller.repoRoot").iterator.map(Path.of(_).toAbsolutePath) ++
      Iterator.iterate(Path.of("").toAbsolutePath)(_.getParent).takeWhile(_ != null)

  private val ToolHeading = """^\d+\. (\S+)$""".r

  private val configExamplePath: Path = findRepoFile("config.example.yaml")

  private val exampleResolutionOptions: ResolutionOptions =
    ResolutionOptions.fromEnvironment().copy(hostPlatform = HostPlatform("linux", "amd64"))

  private val resolvingService: BinaryInstallerService = BinaryInstallerService.resolving(
    FakeHttpTextClient("v1.34.0"),
    DirectBinaryInstaller.default,
    resolutionOptions = exampleResolutionOptions
  )

  private def resolvingServiceWithStateRoot(stateRoot: Path): BinaryInstallerService =
    BinaryInstallerService.resolving(
      FakeHttpTextClient("v1.34.0"),
      DirectBinaryInstaller.default,
      ApplyStateStore.nio(stateRoot),
      resolutionOptions = exampleResolutionOptions
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

private final case class CliRunResult(exitCode: Int, out: String, err: String)

private final case class CommandOptionsCase(
    args: Vector[String],
    recordedOptions: RecordingInstallerService => Option[InstallerOptions],
    expectedOptions: InstallerOptions
)

private object ExceptionalApplyInstallerService extends BinaryInstallerService:
  val message: String = "apply failed after progress started"

  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult = StubBinaryInstallerService.planWithEvents(options, eventObserver)

  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    eventObserver.onEvent(InstallerEvent.DownloadProgress(
      ToolName.unsafe("alpha"),
      "https://example.invalid/alpha",
      0L,
      Some(10L),
      DownloadProgressStatus.Started,
      Duration.ZERO
    ))
    throw IllegalStateException(message)

  def versions(options: InstallerOptions): InstallerResult =
    StubBinaryInstallerService.versions(options)

  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult =
    StubBinaryInstallerService.lock(options, lockOptions)

private final class FakeHttpTextClient(text: String) extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] =
    if url == "https://dl.k8s.io/release/stable.txt" then Right(text)
    else Left(HttpTextError(url, s"unexpected URL $url"))

private final class RedirectingHttpTextClient(text: String, provenance: UrlProvenance)
    extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] = getTextWithProvenance(url).map(_.text)

  override def getTextWithProvenance(url: String): Either[HttpTextError, HttpTextResponse] =
    if url == provenance.initialUrl then Right(HttpTextResponse(text, provenance))
    else Left(HttpTextError(url, s"unexpected URL $url"))

/**
 * Writes a literal payload to a temp artifact, so a CLI fake states only its progress behaviour.
 *
 * Core has an equivalent base for its own fakes, but that one is `private[core]`.
 */
private def testArtifact(url: String, bytes: Array[Byte]): BinaryDownloadArtifact =
  val path = Files.createTempFile("binstaller-cli-download-", ".artifact")
  Files.write(path, bytes)
  val hex = java.security.MessageDigest
    .getInstance("SHA-256")
    .digest(bytes)
    .map(byte => f"${byte & 0xff}%02x")
    .mkString
  val digest = Sha256Digest.fromString(hex) match
    case Right(value) => value
    case Left(error)  => throw java.lang.AssertionError(s"unexpected test digest: $error")
  BinaryDownloadArtifact(path, UrlProvenance.direct(url), digest, bytes.length.toLong)

private final class ProgressBinaryDownloadClient(bytes: Array[Byte]) extends BinaryDownloadClient:

  def downloadArtifactWithProvenance(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, BinaryDownloadArtifact] =
    val halfway = bytes.length.toLong / 2L
    val total   = Some(bytes.length.toLong)
    progressObserver.onProgress(BinaryDownloadProgress.Started(url, total))
    progressObserver.onProgress(BinaryDownloadProgress.Advanced(url, halfway, total))
    progressObserver.onProgress(BinaryDownloadProgress.Finished(url, bytes.length.toLong, total))
    Right(testArtifact(url, bytes))

private final class ConcurrentProgressBinaryDownloadClient(payloads: Map[String, Array[Byte]])
    extends BinaryDownloadClient:

  private val starts = CountDownLatch(payloads.size)

  def downloadArtifactWithProvenance(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, BinaryDownloadArtifact] = payloads.get(url) match
    case None        => Left(BinaryDownloadError(url, s"unexpected URL $url"))
    case Some(bytes) =>
      val total   = Some(bytes.length.toLong)
      val halfway = bytes.length.toLong / 2L
      progressObserver.onProgress(BinaryDownloadProgress.Started(url, total))
      starts.countDown()
      val _ = starts.await(5, TimeUnit.SECONDS)
      progressObserver.onProgress(BinaryDownloadProgress.Advanced(url, halfway, total))
      progressObserver.onProgress(BinaryDownloadProgress.Finished(url, bytes.length.toLong, total))
      Right(testArtifact(url, bytes))

private final class RecordingInstallerService extends BinaryInstallerService:

  private var recordedPlanOptions: Option[InstallerOptions]     = None
  private var recordedApplyOptions: Option[InstallerOptions]    = None
  private var recordedVersionsOptions: Option[InstallerOptions] = None
  private var recordedLockOptions: Option[LockOptions]          = None
  private var recordedLockInstaller: Option[InstallerOptions]   = None

  def planOptions: Option[InstallerOptions] = recordedPlanOptions

  def applyOptions: Option[InstallerOptions] = recordedApplyOptions

  def versionsOptions: Option[InstallerOptions] = recordedVersionsOptions

  def lockOptions: Option[LockOptions] = recordedLockOptions

  def lockInstallerOptions: Option[InstallerOptions] = recordedLockInstaller

  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    recordedPlanOptions = Some(options)
    InstallerResult(Vector("plan"), InstallerRunStatus.Succeeded)

  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    recordedApplyOptions = Some(options)
    InstallerResult(Vector("apply"), InstallerRunStatus.Succeeded)

  def versions(options: InstallerOptions): InstallerResult =
    recordedVersionsOptions = Some(options)
    InstallerResult(Vector("versions"), InstallerRunStatus.Succeeded)

  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult =
    recordedLockInstaller = Some(options)
    recordedLockOptions = Some(lockOptions)
    InstallerResult(Vector("lock"), InstallerRunStatus.Succeeded)
