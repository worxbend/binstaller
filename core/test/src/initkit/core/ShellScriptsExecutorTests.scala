package initkit.core

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.Duration

import initkit.config.*
import utest.*

object ShellScriptsExecutorTests extends TestSuite:
  val tests: Tests = Tests:
    test("renders rustup and miniforge download and execution operations from config example"):
      val operation = shellScriptsOperation("language-toolchains")
      val files = RecordingShellScriptFiles(
        tempPaths = Vector(rustupTemp, miniforgeTemp),
        previewPaths = Vector(rustupPreview, miniforgePreview)
      )
      val commands = shellScriptCommands(operation, Vector(rustupTemp, miniforgeTemp))
      val downloader = RecordingShellScriptDownloader.successful
      val executor = FakeCommandExecutor(
        commands.map(command => FakeCommandResponse(command, CommandResultData.exited(0, duration = Duration.Zero)))
      )
      val installer = new PackageManagerInstallers(
        executor,
        shellScriptDownloader = downloader,
        shellScriptFiles = files
      )

      val outcome = installer.install(PlanOperation.ShellScripts(operation), applyPolicy)

      assert(outcome == PlanOperationOutcome.Completed(Vector("ran 2 shell script(s)")))
      assert(downloader.calls == Vector(
        ShellScriptDownloadCall("https://sh.rustup.rs", rustupTemp),
        ShellScriptDownloadCall(
          "https://github.com/conda-forge/miniforge/releases/latest/download/Miniforge3-Linux-x86_64.sh",
          miniforgeTemp
        )
      ))
      assert(executor.calls == commands)
      assert(commandArgv(commands(0)) == Vector("sh", "-s", "--", "-y", "--default-toolchain", "stable"))
      assert(commands(0).stdinFile == Some(rustupTemp))
      assert(commandArgv(commands(1)) == Vector("bash", miniforgeTemp.toString, "-b", "-p", "${home}/miniforge3"))
      assert(commands(1).stdinFile.isEmpty)
      assert(commands.forall(_.sudo == SudoMode.Disabled))

    test("creates paths skip duplicate shell script execution"):
      val operation = shellScriptsOperation("language-toolchains")
      val files = RecordingShellScriptFiles(
        existing = Set(Path.of("${home}/.cargo/bin/rustc")),
        tempPaths = Vector(miniforgeTemp),
        previewPaths = Vector(rustupPreview, miniforgePreview)
      )
      val commands = shellScriptCommands(
        operation.copy(spec = InstallerSpec.ShellScripts(Vector(miniforgeItem(operation)))),
        Vector(miniforgeTemp)
      )
      val downloader = RecordingShellScriptDownloader.successful
      val executor = FakeCommandExecutor(
        commands.map(command => FakeCommandResponse(command, CommandResultData.exited(0, duration = Duration.Zero)))
      )
      val installer = new PackageManagerInstallers(
        executor,
        shellScriptDownloader = downloader,
        shellScriptFiles = files
      )

      val outcome = installer.install(PlanOperation.ShellScripts(operation), applyPolicy)

      assert(outcome == PlanOperationOutcome.Completed(Vector("ran 1 shell script(s), skipped 1 shell script(s)")))
      assert(downloader.calls.map(_.destination) == Vector(miniforgeTemp))
      assert(executor.calls == commands)

    test("configured shell and args preserve argv boundaries"):
      val item = ShellScriptItem(
        name = "custom",
        url = "https://example.test/custom.sh",
        shell = "bash",
        args = Vector("--flag=value with spaces", "--", "literal * value"),
        creates = None
      )
      val command = ShellScriptsExecutor.commandSpec(item, Path.of("/tmp/custom.sh"))

      assert(commandArgv(command) == Vector("bash", "/tmp/custom.sh", "--flag=value with spaces", "--", "literal * value"))
      assert(command.stdinFile.isEmpty)

    test("dry-run previews downloads and execution without mutating files"):
      val operation = shellScriptsOperation("language-toolchains")
      val files = RecordingShellScriptFiles(
        tempPaths = Vector(rustupTemp, miniforgeTemp),
        previewPaths = Vector(rustupPreview, miniforgePreview)
      )
      val downloader = RecordingShellScriptDownloader.successful
      val executor = FakeCommandExecutor(Vector.empty)
      val installer = new PackageManagerInstallers(
        executor,
        shellScriptDownloader = downloader,
        shellScriptFiles = files
      )

      val outcome = installer.install(PlanOperation.ShellScripts(operation), dryRunPolicy)
      val dryRun = outcome match
        case PlanOperationOutcome.DryRun(data) => data
        case other                            => fail(s"expected dry-run outcome, got $other")

      assert(downloader.calls.isEmpty)
      assert(executor.calls.isEmpty)
      assert(files.created.isEmpty)
      assert(dryRun.actions == Vector(
        DryRunAction.Message("download shell script 'rustup' from https://sh.rustup.rs to /preview/rustup.sh"),
        DryRunAction.Command(
          argv = Vector("sh", "-s", "--", "-y", "--default-toolchain", "stable"),
          shell = None,
          sudo = false,
          workingDirectory = None,
          stdinFile = Some("/preview/rustup.sh")
        ),
        DryRunAction.Message(
          "download shell script 'miniforge' from https://github.com/conda-forge/miniforge/releases/latest/download/Miniforge3-Linux-x86_64.sh to /preview/miniforge.sh"
        ),
        DryRunAction.Command(
          argv = Vector("bash", "/preview/miniforge.sh", "-b", "-p", "${home}/miniforge3"),
          shell = None,
          sudo = false,
          workingDirectory = None
        )
      ))

  private val rustupTemp: Path =
    Path.of("/tmp/initkit-rustup.sh")

  private val miniforgeTemp: Path =
    Path.of("/tmp/initkit-miniforge.sh")

  private val rustupPreview: Path =
    Path.of("/preview/rustup.sh")

  private val miniforgePreview: Path =
    Path.of("/preview/miniforge.sh")

  private val dryRunPolicy: ExecutionPolicy =
    ExecutionPolicy(
      mode = ExecutionRunMode.DryRun,
      continueOnError = false,
      requireSudo = true,
      reboot = RebootExecutionPolicy(allowed = false, prompt = true)
    )

  private val applyPolicy: ExecutionPolicy =
    dryRunPolicy.copy(mode = ExecutionRunMode.Apply)

  private lazy val manifest: Manifest =
    ManifestLoader.loadValidated(exampleConfigPath) match
      case Right(value) => value
      case Left(error)  => fail(error.message)

  private def shellScriptsOperation(name: String): InstallerPlanOperation[InstallerSpec.ShellScripts] =
    val (entry, index) = manifest.spec.plan.zipWithIndex
      .find((entry, _) => entry.name.contains(name))
      .getOrElse(fail(s"plan entry '$name' not found"))

    PlanOperation.decode(index, entry) match
      case Right(PlanOperation.ShellScripts(operation)) => operation
      case Right(other)                                 => fail(s"plan entry '$name' is not a shell-scripts operation: $other")
      case Left(errors)                                 => fail(errors.map(_.message).mkString("; "))

  private def shellScriptCommands(
      operation: InstallerPlanOperation[InstallerSpec.ShellScripts],
      tempPaths: Vector[Path]
  ): Vector[CommandSpec] =
    operation.spec.items.zip(tempPaths).map: (item, tempPath) =>
      ShellScriptsExecutor.commandSpec(item, tempPath)

  private def miniforgeItem(operation: InstallerPlanOperation[InstallerSpec.ShellScripts]): ShellScriptItem =
    operation.spec.items
      .find(_.name == "miniforge")
      .getOrElse(fail("miniforge item not found"))

  private def commandArgv(command: CommandSpec): Vector[String] =
    command.invocation match
      case CommandInvocation.Direct(argv) => argv.map(_.value)
      case CommandInvocation.Shell(_, _)  => fail("expected direct command")

  private def exampleConfigPath: Path =
    Iterator
      .iterate(Path.of("").toAbsolutePath.normalize())(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("config.example.yaml"))
      .find(Files.isRegularFile(_))
      .getOrElse(throw new java.lang.AssertionError("config.example.yaml fixture not found"))

  private def fail(message: String): Nothing =
    throw new java.lang.AssertionError(message)

private final case class ShellScriptDownloadCall(url: String, destination: Path)

private final class RecordingShellScriptDownloader private (
    result: Either[ShellScriptDownloadError, Unit]
) extends ShellScriptDownloader:
  private val callsRef = AtomicReference(Vector.empty[ShellScriptDownloadCall])

  def calls: Vector[ShellScriptDownloadCall] =
    callsRef.get()

  override def download(url: String, destination: Path): Either[ShellScriptDownloadError, Unit] =
    callsRef.set(callsRef.get() :+ ShellScriptDownloadCall(url, destination))
    result

private object RecordingShellScriptDownloader:
  def successful: RecordingShellScriptDownloader =
    new RecordingShellScriptDownloader(Right(()))

private final class RecordingShellScriptFiles(
    existing: Set[Path] = Set.empty,
    tempPaths: Vector[Path],
    previewPaths: Vector[Path]
) extends ShellScriptFiles:
  private val stateRef = AtomicReference(
    RecordingShellScriptFilesState(
      remainingTempPaths = tempPaths,
      remainingPreviewPaths = previewPaths,
      created = Vector.empty,
      deleted = Vector.empty
    )
  )

  def created: Vector[Path] =
    stateRef.get().created

  override def exists(path: Path): Boolean =
    existing.contains(path)

  override def createTempScript(itemName: String): Either[ShellScriptFileError, Path] =
    val state = stateRef.get()
    state.remainingTempPaths.headOption match
      case Some(path) =>
        stateRef.set(
          state.copy(
            remainingTempPaths = state.remainingTempPaths.tail,
            created = state.created :+ path
          )
        )
        Right(path)
      case None =>
        Left(ShellScriptFileError(s"no temp path configured for $itemName"))

  override def previewTempScriptPath(itemName: String): Path =
    val state = stateRef.get()
    state.remainingPreviewPaths.headOption match
      case Some(path) =>
        stateRef.set(state.copy(remainingPreviewPaths = state.remainingPreviewPaths.tail))
        path
      case None =>
        throw new java.lang.AssertionError(s"no preview path configured for $itemName")

  override def deleteIfExists(path: Path): Unit =
    val state = stateRef.get()
    stateRef.set(state.copy(deleted = state.deleted :+ path))

private final case class RecordingShellScriptFilesState(
    remainingTempPaths: Vector[Path],
    remainingPreviewPaths: Vector[Path],
    created: Vector[Path],
    deleted: Vector[Path]
)
