package binstaller.cli

import binstaller.core.BinaryInstallerService
import binstaller.core.ApplyParallelism
import binstaller.core.BinaryDownloadArtifact
import binstaller.core.Sha256Digest
import binstaller.core.BinaryDownloadClient
import binstaller.core.BinaryDownloadError
import binstaller.core.BinaryDownloadProgress
import binstaller.core.BinaryDownloadProgressObserver
import binstaller.core.DirectBinaryInstaller
import binstaller.core.HttpTextClient
import binstaller.core.HttpTextError
import binstaller.core.HttpTextResponse
import binstaller.core.HostPlatform
import binstaller.core.InstallFileSystem
import binstaller.core.InstallerEventObserver
import binstaller.core.InstallerOptions
import binstaller.core.InstallerResult
import binstaller.core.NewerVersionStatus
import binstaller.core.InstallerRunStatus
import binstaller.core.VersionSummaryRow
import binstaller.core.LockedApplyMode
import binstaller.core.LockOptions
import binstaller.core.ResetState
import binstaller.core.ResolutionOptions
import binstaller.core.ApplyStateStore
import binstaller.core.UrlProvenance
import binstaller.core.UrlRedirectHop
import utest.*

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object CliModuleTest extends TestSuite:

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

    test("apply forwards locked options"):
      val service = RecordingInstallerService()
      val result  = runCli(
        Vector(
          "apply",
          "--config",
          "profile.yaml",
          "--locked",
          "--lock-file",
          "custom.lock.json"
        ),
        service
      )

      assert(result.exitCode == 0)
      assert(service.applyOptions.exists(_.lockedApply == LockedApplyMode.Enabled))
      assert(service.applyOptions.exists(_.lockPath == "custom.lock.json"))

    test("apply forwards parallelism"):
      val service = RecordingInstallerService()
      val result  = runCli(
        Vector("apply", "--config", "profile.yaml", "--parallelism", "8"),
        service
      )

      assert(result.exitCode == 0)
      assert(service.applyOptions.exists(_.applyParallelism.value == 8))

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
      assert(CliOutputStyle.forProcessOutput(Map("FORCE_COLOR" -> "1"), interactive = false) == Ansi)
      assert(
        CliOutputStyle.forProcessOutput(Map("CLICOLOR_FORCE" -> "1"), interactive = false) == Ansi
      )
      assert(CliOutputStyle.forProcessOutput(Map("FORCE_COLOR" -> "0"), interactive = false) == Plain)
      // NO_COLOR wins even against a force request:
      assert(
        CliOutputStyle.forProcessOutput(
          Map("NO_COLOR" -> "", "FORCE_COLOR" -> "1"),
          interactive = true
        ) == Plain
      )

    test("plan forwards locked options"):
      val service = RecordingInstallerService()
      val result  = runCli(
        Vector(
          "plan",
          "--config",
          "profile.yaml",
          "--locked",
          "--lock-file",
          "custom.lock.json"
        ),
        service
      )

      assert(result.exitCode == 0)
      assert(service.planOptions.exists(_.lockedApply == LockedApplyMode.Enabled))
      assert(service.planOptions.exists(_.lockPath == "custom.lock.json"))

    test("lock forwards lock file path and selection"):
      val service = RecordingInstallerService()
      val result  = runCli(
        Vector(
          "lock",
          "--config",
          "profile.yaml",
          "--lock-file",
          "custom.lock.json",
          "--only",
          "alpha"
        ),
        service
      )

      assert(result.exitCode == 0)
      assert(result.out.contains("lock"))
      assert(service.lockOptions.exists(_.outputPath == "custom.lock.json"))
      assert(service.lockInstallerOptions.exists(_.selection.only == Vector("alpha")))

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

    test("a version containing two consecutive spaces keeps its own column"):
      // The old renderer recovered columns by splitting core's padded output on runs of two or
      // more spaces, so a value containing two spaces silently split into the wrong columns. The
      // rows now cross the boundary as data, so the value survives intact.
      val awkward = VersionSummaryRow("alpha", "1.0  beta", NewerVersionStatus.UpToDate)
      val result  = InstallerResult(
        Vector("binstaller versions", "package  version    newer version"),
        InstallerRunStatus.Succeeded,
        versionRows = Vector(awkward)
      )

      val plain = stripAnsi(
        CliVersionsOutput.colorLines(result, CliOutputStyle.Plain).mkString("\n")
      )

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
      val tempRoot = Files.createTempDirectory("binstaller-cli-dry-symlinks")
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
      val tempRoot  = Files.createTempDirectory("binstaller-cli-test")
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
      val tempRoot = Files.createTempDirectory("binstaller-cli-progress")
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
      val tempRoot = Files.createTempDirectory("binstaller-cli-progress-plain")
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

    test("apply colours result lines by their status, not by their wording"):
      // The colour must come from the typed status core pairs with each rendered line. A prefix
      // test on the text would keep passing here while silently losing its colour the moment core
      // reworded "installed " or "failed ".
      val tempRoot = Files.createTempDirectory("binstaller-cli-result-colour")
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
      val tempRoot = Files.createTempDirectory("binstaller-cli-parallel-progress")
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

  private val resolvingService: BinaryInstallerService =
    BinaryInstallerService.resolving(
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

/** Writes a literal payload to a temp artifact, so a CLI fake states only its progress behaviour.
 *
 *  Core has an equivalent base for its own fakes, but that one is `private[core]`.
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
