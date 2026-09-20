package binstaller.cli

import binstaller.core.BinaryInstallerService
import binstaller.core.ApplyParallelism
import binstaller.core.ApplyParallelismError
import binstaller.core.HttpTextClient
import binstaller.core.InstallerOptions
import binstaller.core.InstallerResult
import binstaller.core.InstallerRunStatus
import binstaller.core.LockedApplyMode
import binstaller.core.LockOptions
import binstaller.core.ResetState
import binstaller.core.ToolSelection
import binstaller.core.VerboseOutput
import picocli.CommandLine
import picocli.CommandLine.Option as CliOption
import picocli.CommandLine.Spec as CliSpec
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.ScopeType

import java.io.PrintWriter
import java.nio.file.Path
import java.util.concurrent.Callable

/** Picocli-backed command boundary for the `binstaller` process. */
object CliModule:

  /** Run the CLI with process stdout/stderr. */
  def run(args: Vector[String]): Int = run(
    args,
    PrintWriter(System.out, true),
    PrintWriter(System.err, true),
    CliOutputStyle.forProcessOutput
  )

  /** Run the CLI with injectable writers for tests or alternate launchers. */
  def run(args: Vector[String], out: PrintWriter, err: PrintWriter): Int =
    run(args, out, err, CliOutputStyle.Plain)

  private[cli] def run(
      args: Vector[String],
      out: PrintWriter,
      err: PrintWriter,
      outputStyle: CliOutputStyle
  ): Int = commandLine(productionService(err), out, err, outputStyle).execute(args*)

  /** Build the root command with an injectable core service. */
  def commandLine(
      service: BinaryInstallerService,
      out: PrintWriter,
      err: PrintWriter
  ): CommandLine = commandLine(service, out, err, CliOutputStyle.Plain)

  private[cli] def commandLine(
      service: BinaryInstallerService,
      out: PrintWriter,
      err: PrintWriter,
      outputStyle: CliOutputStyle
  ): CommandLine =
    val root        = BinstallerCommand(out)
    val commandLine = CommandLine(root)
    commandLine.setOut(out)
    commandLine.setErr(err)
    commandLine.addSubcommand(
      "plan",
      subcommandLine(PlanCommand(root, service, out), out, err)
    )
    commandLine.addSubcommand(
      "apply",
      subcommandLine(ApplyCommand(root, service, out, err, outputStyle), out, err)
    )
    commandLine.addSubcommand(
      "versions",
      subcommandLine(VersionsCommand(root, service, out, outputStyle), out, err)
    )
    commandLine.addSubcommand(
      "lock",
      subcommandLine(LockCommand(root, service, out), out, err)
    )
    RootHelpLogo.install(commandLine, outputStyle)
    commandLine

  private def productionService(err: PrintWriter): BinaryInstallerService =
    BinaryInstallerService.resolving(HttpTextClient.jdk, TerminalSudoCredentialProvider(err))

  private def subcommandLine(
      command: Callable[Integer],
      out: PrintWriter,
      err: PrintWriter
  ): CommandLine =
    val commandLine = CommandLine(command)
    commandLine.setOut(out)
    commandLine.setErr(err)
    commandLine

private[cli] final case class GlobalOptions(
    configPath: Option[String],
    statePath: Option[String],
    resetState: ResetState,
    verboseOutput: VerboseOutput
)

private[cli] object GlobalOptions:

  def empty: GlobalOptions = GlobalOptions(
    configPath = None,
    statePath = None,
    resetState = ResetState.Disabled,
    verboseOutput = VerboseOutput.Disabled
  )

private[cli] object DefaultConfig:
  def path: String = Path.of("config.yaml").toAbsolutePath.normalize().toString

@Command(
  name = "binstaller",
  mixinStandardHelpOptions = true,
  sortOptions = false,
  synopsisSubcommandLabel = "COMMAND",
  description = Array("Inspect and apply binary installer manifests.")
)
private[cli] final class BinstallerCommand(out: PrintWriter) extends Callable[Integer]:
  private var globalOptions: GlobalOptions     = GlobalOptions.empty
  private var commandSpec: Option[CommandSpec] = None

  // Injected through a method, not a field: the native-image reflect config registers this class's
  // methods but not its fields, so field injection would NPE in the shipped binary.
  @CliSpec
  def setCommandSpec(spec: CommandSpec): Unit = commandSpec = Some(spec)

  @CliOption(
    names = Array("--config"),
    paramLabel = "FILE",
    scope = ScopeType.INHERIT,
    description = Array("YAML profile path. Default: cwd config.yaml.")
  )
  def setConfigPath(value: String): Unit =
    globalOptions = globalOptions.copy(configPath = Some(value))

  @CliOption(
    names = Array("--state"),
    paramLabel = "FILE",
    scope = ScopeType.INHERIT,
    description = Array("Path to the execution state file.")
  )
  def setStatePath(value: String): Unit =
    globalOptions = globalOptions.copy(statePath = Some(value))

  @CliOption(
    names = Array("--reset-state"),
    scope = ScopeType.INHERIT,
    description = Array("Ignore any saved execution state.")
  )
  def setResetState(value: Boolean): Unit =
    globalOptions = globalOptions.copy(resetState = ResetState.fromFlag(value))

  @CliOption(
    names = Array("--verbose"),
    scope = ScopeType.INHERIT,
    description = Array("Show additional command diagnostics.")
  )
  def setVerboseOutput(value: Boolean): Unit =
    globalOptions = globalOptions.copy(verboseOutput = VerboseOutput.fromFlag(value))

  def installerOptions: InstallerOptions = InstallerOptions(
    configPath = globalOptions.configPath.getOrElse(DefaultConfig.path),
    statePath = globalOptions.statePath,
    resetState = globalOptions.resetState,
    verboseOutput = globalOptions.verboseOutput
  )

  override def call(): Integer =
    commandSpec.foreach(_.commandLine().usage(out))
    Integer.valueOf(CommandLine.ExitCode.USAGE)

private[cli] abstract class ConfiguredCommand(
    root: BinstallerCommand,
    out: PrintWriter
) extends Callable[Integer]:

  protected def installerOptions: InstallerOptions = root.installerOptions

  protected def executeWithOptions(
      action: InstallerOptions => InstallerResult
  ): Integer = executeWithOptions(action, identity)

  protected def executeWithOptions(
      action: InstallerOptions => InstallerResult,
      transformResult: InstallerResult => InstallerResult
  ): Integer = render(transformResult(action(installerOptions)))

  protected def render(result: InstallerResult): Integer =
    result.lines.foreach(out.println)
    Integer.valueOf(CliExitCode.of(result.status))

private[cli] abstract class SelectableCommand(
    root: BinstallerCommand,
    out: PrintWriter
) extends ConfiguredCommand(root, out):

  private var onlyTools: Vector[String]    = Vector.empty
  private var skippedTools: Vector[String] = Vector.empty

  @CliOption(
    names = Array("--only"),
    paramLabel = "TOOL",
    arity = "1..*",
    split = "[,\\s]+",
    splitSynopsisLabel = ",",
    description = Array(
      "Select only the named tool. Accepts a comma- or space-separated list, and the flag may also be repeated."
    )
  )
  def addOnlyTool(values: Array[String]): Unit = onlyTools = onlyTools ++ values

  @CliOption(
    names = Array("--skip"),
    paramLabel = "TOOL",
    arity = "1..*",
    split = "[,\\s]+",
    splitSynopsisLabel = ",",
    description = Array(
      "Omit the named tool. Accepts a comma- or space-separated list, and the flag may also be repeated."
    )
  )
  def addSkippedTool(values: Array[String]): Unit = skippedTools = skippedTools ++ values

  override protected def installerOptions: InstallerOptions =
    super.installerOptions.copy(selection = ToolSelection(onlyTools, skippedTools))

/**
 * A selectable command that can also be pinned to a lock file.
 *
 * `plan` and `apply` both accept `--locked` and `--lock-file`, and previously declared the same two
 * fields, two annotations and two setters each. Picocli picks up annotated setters from a
 * superclass -- which is how `--only`/`--skip` already reach both -- so the flags are declared once
 * here instead of drifting between two copies.
 */
private[cli] abstract class LockAwareCommand(
    root: BinstallerCommand,
    out: PrintWriter
) extends SelectableCommand(root, out):
  private var lockedApply: LockedApplyMode = LockedApplyMode.Disabled
  private var lockPath: String             = LockOptions.defaultOutputPath

  @CliOption(
    names = Array("--locked"),
    description = Array("Require a compatible JSON lock file before running.")
  )
  def setLockedApply(value: Boolean): Unit = lockedApply = LockedApplyMode.fromFlag(value)

  @CliOption(
    names = Array("--lock-file"),
    paramLabel = "FILE",
    description = Array("Path to the JSON lock file used by --locked.")
  )
  def setLockPath(value: String): Unit = lockPath = value

  override protected def installerOptions: InstallerOptions =
    super.installerOptions.copy(lockPath = lockPath, lockedApply = lockedApply)

@Command(
  name = "plan",
  mixinStandardHelpOptions = true,
  description = Array("Render the binary installer plan without changing files.")
)
private[cli] final class PlanCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter
) extends LockAwareCommand(root, out):

  override def call(): Integer = executeWithOptions(service.plan)

@Command(
  name = "apply",
  mixinStandardHelpOptions = true,
  description = Array("Apply the binary installer plan.")
)
private[cli] final class ApplyCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter,
    err: PrintWriter,
    outputStyle: CliOutputStyle
) extends LockAwareCommand(root, out):
  // Stored raw and validated in call(): validating in the setter would require throwing, and the
  // @Spec-based ParameterException route needs field reflection that the native-image build does
  // not register (it would NPE in the shipped binary), reintroducing the raw stack trace this
  // guards against.
  private var parallelismValue: Int = ApplyParallelism.default.value

  @CliOption(
    names = Array("--parallelism"),
    paramLabel = "N",
    description = Array("Number of tools to download and stage concurrently. Default: 4.")
  )
  def setParallelism(value: Int): Unit = parallelismValue = value

  override def call(): Integer = ApplyParallelism.fromInt(parallelismValue) match
    case Left(error) =>
      // stderr, not stdout: `apply` output is meant to be pipeable, and a usage complaint that
      // lands in the piped stream corrupts it. The flag name is added here because only the CLI
      // knows how this option is spelled.
      err.println(s"--parallelism ${ApplyParallelismError.render(error)}")
      Integer.valueOf(CommandLine.ExitCode.USAGE)
    case Right(parallelism) =>
      val options = installerOptions.copy(applyParallelism = parallelism)
      render(runApply(options))

  private def runApply(options: InstallerOptions): InstallerResult =
    val eventRenderer = CliApplyEventRenderer(out, outputStyle)
    val result        =
      try service.applyWithEvents(options, eventRenderer)
      finally eventRenderer.finish()
    result.copy(lines =
      CliApplyOutput.colorLines(result.lines, result.renderedTerminalLines, outputStyle) ++
        eventRenderer.summaryLines
    )

@Command(
  name = "versions",
  mixinStandardHelpOptions = true,
  description = Array("Resolve and print binary tool versions.")
)
private[cli] final class VersionsCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter,
    outputStyle: CliOutputStyle
) extends SelectableCommand(root, out):

  override def call(): Integer = executeWithOptions(
    service.versions,
    result =>
      if result.status == InstallerRunStatus.Succeeded then
        result.copy(lines = CliVersionsOutput.colorLines(result, outputStyle))
      else result
  )

@Command(
  name = "lock",
  mixinStandardHelpOptions = true,
  description = Array("Resolve and write a JSON lock file without installing tools.")
)
private[cli] final class LockCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter
) extends SelectableCommand(root, out):
  private var outputPath: String = LockOptions.defaultOutputPath

  @CliOption(
    names = Array("--lock-file"),
    paramLabel = "FILE",
    description = Array("Path to the JSON lock file to write.")
  )
  def setOutputPath(value: String): Unit = outputPath = value

  override def call(): Integer =
    executeWithOptions(options => service.lock(options, LockOptions(outputPath)))
