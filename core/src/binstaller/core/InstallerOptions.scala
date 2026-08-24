package binstaller.core

/** Whether apply should ignore a saved execution state file. */
enum ResetState:
  case Enabled, Disabled

/** Helpers for converting CLI flags into reset-state policy. */
object ResetState:
  /** Convert a boolean CLI flag into [[ResetState]]. */
  def fromFlag(value: Boolean): ResetState = if value then Enabled else Disabled

/** Whether command diagnostics and detailed operation lines should be emitted. */
enum VerboseOutput:
  case Enabled, Disabled

/** Helpers for converting CLI flags into verbose-output policy. */
object VerboseOutput:
  /** Convert a boolean CLI flag into [[VerboseOutput]]. */
  def fromFlag(value: Boolean): VerboseOutput = if value then Enabled else Disabled

/** Runtime options shared by plan, apply, versions, and lock entrypoints. */
final case class InstallerOptions(
    configPath: String,
    statePath: Option[String],
    resetState: ResetState,
    verboseOutput: VerboseOutput,
    selection: ToolSelection = ToolSelection.all,
    lockPath: String = LockOptions.defaultOutputPath,
    lockedApply: LockedApplyMode = LockedApplyMode.Disabled,
    applyParallelism: ApplyParallelism = ApplyParallelism.default
)

/** Bounded parallelism for apply-time artifact download and staging. */
final case class ApplyParallelism(value: Int)

/** Constructors and validation for apply parallelism. */
object ApplyParallelism:
  /** Default number of tools prepared concurrently by `apply`. */
  val default: ApplyParallelism = ApplyParallelism(4)

  /** Build a positive parallelism value from CLI or embedded caller input. */
  def fromInt(value: Int): Either[String, ApplyParallelism] =
    if value >= 1 then Right(ApplyParallelism(value))
    else Left("parallelism must be at least 1")

/** Rendered command lines plus the outcome of the run.
 *
 *  Deliberately not a process exit code. A POSIX status is a property of how this program is
 *  delivered — as a CLI — not of what the installer did, so the translation happens once, in
 *  `binstaller.cli.CliExitCode`. A caller embedding core in something that is not a process gets
 *  an answer it can act on rather than an integer it has to decode.
 */
final case class InstallerResult(
    lines: Vector[String],
    status: InstallerRunStatus,
    terminalResults: Vector[TerminalToolResult] = Vector.empty,
    skippedTools: Int = 0
)

/** Tool selection requested by `--only` and `--skip`. */
final case class ToolSelection(only: Vector[String], skip: Vector[String])

/** Tool-selection constructors. */
object ToolSelection:
  /** Select every resolved tool. */
  def all: ToolSelection = ToolSelection(Vector.empty, Vector.empty)
