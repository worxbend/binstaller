package binstaller.cli

import binstaller.core.BinaryInstallerService
import binstaller.core.HttpTextClient
import binstaller.core.HttpTextError
import binstaller.core.InstallerOptions
import binstaller.core.InstallerResult
import binstaller.core.ResetState
import utest.*

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

object CliModuleTest extends TestSuite:

  val tests: Tests = Tests:
    test("module path includes upstream modules"):
      assert(CliModule.modulePath == Vector("config", "core", "cli"))

    test("help describes the binstaller binary installer"):
      val result = runCli(Vector("--help"))

      assert(result.exitCode == 0)
      assert(result.out.contains("binstaller"))
      assert(result.out.contains("binary installer"))

    test("help lists supported commands"):
      val result = runCli(Vector("--help"))

      assert(result.out.contains("plan"))
      assert(result.out.contains("apply"))
      assert(result.out.contains("versions"))

    test("help omits out-of-scope first-class commands"):
      val result = runCli(Vector("--help"))

      assert(!result.out.contains("apt"))
      assert(!result.out.contains("dotfiles"))
      assert(!result.out.contains("Nerd Fonts"))
      assert(!result.out.contains("TUI"))

    test("plan requires config"):
      val result = runCli(Vector("plan"))

      assert(result.exitCode != 0)
      assert(result.err.trim == "Missing required option: --config")

    test("apply requires config"):
      val result = runCli(Vector("apply"))

      assert(result.exitCode != 0)
      assert(result.err.trim == "Missing required option: --config")

    test("versions requires config"):
      val result = runCli(Vector("versions"))

      assert(result.exitCode != 0)
      assert(result.err.trim == "Missing required option: --config")

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

    test("apply dry-run renders every sudo symlink command and marks sudo risk"):
      val result = runCli(
        Vector("apply", "--dry-run", "--config", configExamplePath.toString, "--only", "neovim"),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(result.out.contains("sudo risk: YES"))
      assert(result.out.linesIterator.count(_.contains("sudo ln -sfn")) == 5)

    test("apply dry-run renders local and sudo symlink actions without executing them"):
      val tempRoot = Files.createTempDirectory("binstaller-cli-dry-symlinks")
      val appsDir  = tempRoot.resolve("apps")
      val config   = writeConfig(tempRoot, noWriteYaml(appsDir, tempRoot.resolve("state.json")))

      val result = runCli(
        Vector("apply", "--dry-run", "--config", config.toString),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(result.out.contains("[local] ln -sfn"))
      assert(result.out.contains("[sudo risk] sudo ln -sfn"))
      assert(!Files.exists(appsDir))

    test("plan and apply dry-run do not create install or state paths"):
      val tempRoot  = Files.createTempDirectory("binstaller-cli-test")
      val appsDir   = tempRoot.resolve("apps")
      val stateFile = tempRoot.resolve("state.json")
      val config    = writeConfig(tempRoot, noWriteYaml(appsDir, stateFile))

      val planResult   = runCli(Vector("plan", "--config", config.toString), resolvingService)
      val dryRunResult = runCli(
        Vector("apply", "--dry-run", "--config", config.toString),
        resolvingService
      )

      assert(planResult.exitCode == 0)
      assert(dryRunResult.exitCode == 0)
      assert(!Files.exists(appsDir))
      assert(!Files.exists(stateFile))
      assert(!Files.exists(appsDir.resolve("alpha")))

  private def runCli(
      args: Vector[String],
      service: BinaryInstallerService = BinaryInstallerService.placeholder
  ): CliRunResult =
    val outBuffer = StringWriter()
    val errBuffer = StringWriter()
    val out       = PrintWriter(outBuffer, true)
    val err       = PrintWriter(errBuffer, true)
    val exitCode  = CliModule.commandLine(service, out, err).execute(args*)
    CliRunResult(exitCode, outBuffer.toString, errBuffer.toString)

  private def renderedToolNames(output: String): Vector[String] =
    output.linesIterator.toVector.collect:
      case ToolHeading(name) => name

  private def writeConfig(tempRoot: Path, content: String): Path =
    val path = tempRoot.resolve("profile.yaml")
    Files.writeString(path, content)
    path

  private def noWriteYaml(appsDir: Path, stateFile: Path): String =
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

  private def findRepoFile(name: String): Path = Iterator
    .iterate(Path.of("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .map(_.resolve(name))
    .find(Files.isRegularFile(_))
    .getOrElse(
      throw java.lang.AssertionError(s"could not find $name from ${Path.of("").toAbsolutePath}")
    )

  private val ToolHeading = """^\d+\. (\S+)$""".r

  private val configExamplePath: Path = findRepoFile("config.example.yaml")

  private val resolvingService: BinaryInstallerService =
    BinaryInstallerService.resolving(FakeHttpTextClient("v1.34.0"))

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

private final class RecordingInstallerService extends BinaryInstallerService:

  private var recordedApplyOptions: Option[InstallerOptions] = None

  def applyOptions: Option[InstallerOptions] = recordedApplyOptions

  def plan(options: InstallerOptions): InstallerResult = InstallerResult(Vector("plan"), 0)

  def apply(options: InstallerOptions): InstallerResult =
    recordedApplyOptions = Some(options)
    InstallerResult(Vector("apply"), 0)

  def versions(options: InstallerOptions): InstallerResult = InstallerResult(Vector("versions"), 0)
