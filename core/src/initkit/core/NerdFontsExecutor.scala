package initkit.core

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import initkit.config.*

final class NerdFontsExecutor(
    commandExecutor: CommandExecutor,
    files: NerdFontsFiles = NerdFontsFiles.Jvm
):
  def install(
      operation: InstallerPlanOperation[InstallerSpec.NerdFonts],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    policy.mode match
      case ExecutionRunMode.DryRun =>
        PlanOperationOutcome.DryRun(NerdFontsExecutor.dryRunData(operation))
      case ExecutionRunMode.Apply =>
        applyFonts(operation)

  private def applyFonts(
      operation: InstallerPlanOperation[InstallerSpec.NerdFonts]
  ): PlanOperationOutcome =
    writeConfigIfRequested(operation.spec.config) match
      case Left(failure) =>
        failed(operation.summary, failure)
      case Right(configWritten) =>
        runCommands(operation) match
          case Left(failure) =>
            failed(operation.summary, failure)
          case Right(results) =>
            PlanOperationOutcome.Completed(Vector(completionDetail(configWritten, results.size)))

  private def writeConfigIfRequested(config: GeneratedConfig): Either[NerdFontsFailure, Boolean] =
    if config.create.contains(true) then
      config.content match
        case Some(content) =>
          val path = Path.of(config.path)
          val rendered = NerdFontsExecutor.renderConfig(content)
          files.writeConfig(path, rendered).left.map(error => NerdFontsFailure.Config(path, error.message)).map(_ => true)
        case None =>
          Left(NerdFontsFailure.Config(Path.of(config.path), "config.create is true but config.content is missing"))
    else Right(false)

  private def runCommands(
      operation: InstallerPlanOperation[InstallerSpec.NerdFonts]
  ): Either[NerdFontsFailure, Vector[CommandResult]] =
    var results = Vector.empty[CommandResult]
    var failure = Option.empty[NerdFontsFailure]

    NerdFontsExecutor.commandSpecs(operation).foreach: command =>
      if failure.isEmpty then
        val result = commandExecutor.run(command)
        results = results :+ result
        if !result.succeeded then failure = Some(NerdFontsFailure.Command(result))

    failure match
      case Some(value) => Left(value)
      case None        => Right(results)

  private def failed(summary: PlanOperationSummary, failure: NerdFontsFailure): PlanOperationOutcome =
    PlanOperationOutcome.Failed(
      PlanFailure(
        operation = summary,
        message = failure.message,
        exitCode = failure.exitCode
      )
    )

  private def completionDetail(configWritten: Boolean, commandCount: Int): String =
    val configDetail = Option.when(configWritten)("wrote Nerd Fonts config").toVector
    val commandDetail = commandCount match
      case 0 => Vector("ran no Nerd Fonts commands")
      case 1 => Vector("ran 1 Nerd Fonts command")
      case _ => Vector(s"ran $commandCount Nerd Fonts commands")

    (configDetail ++ commandDetail).mkString("; ")

private enum NerdFontsFailure:
  case Config(path: Path, detail: String)
  case Command(result: CommandResult)

  def message: String =
    this match
      case Config(path, detail) =>
        s"Nerd Fonts config write failed for $path: $detail"
      case Command(result) =>
        s"Nerd Fonts command failed: ${NerdFontsExecutor.describe(result.spec)} " +
          s"(${CommandsExecutor.describeTermination(result.termination)})"

  def exitCode: Option[Int] =
    this match
      case Command(result) => result.exitCode
      case Config(_, _)    => None

trait NerdFontsFiles:
  def writeConfig(path: Path, content: String): Either[NerdFontsFileError, Unit]

final case class NerdFontsFileError(message: String)

object NerdFontsFiles:
  val Jvm: NerdFontsFiles =
    new NerdFontsFiles:
      override def writeConfig(path: Path, content: String): Either[NerdFontsFileError, Unit] =
        try
          val absolutePath = path.toAbsolutePath.normalize()
          Option(absolutePath.getParent).foreach(Files.createDirectories(_))
          Files.writeString(absolutePath, content, StandardCharsets.UTF_8)
          Right(())
        catch
          case error: IOException =>
            Left(NerdFontsFileError(error.getMessage))
          case error: SecurityException =>
            Left(NerdFontsFileError(error.getMessage))

object NerdFontsExecutor:
  def commandSpecs(operation: InstallerPlanOperation[InstallerSpec.NerdFonts]): Vector[CommandSpec] =
    previewCommandSpec(operation.spec).toVector :+ applyCommandSpec(operation.spec)

  def applyCommandSpec(spec: InstallerSpec.NerdFonts): CommandSpec =
    toolCommand(spec.tool, extraArgs = Vector.empty)

  def previewCommandSpec(spec: InstallerSpec.NerdFonts): Option[CommandSpec] =
    spec.preview.filter(_.enabled.contains(true)).map: preview =>
      toolCommand(spec.tool, extraArgs = preview.args)

  def dryRunData(operation: InstallerPlanOperation[InstallerSpec.NerdFonts]): DryRunOperationData =
    DryRunOperationData(
      operation = operation.summary,
      actions = configDryRunActions(operation.spec.config) ++ commandSpecs(operation).map(dryRunCommand)
    )

  def renderConfig(content: RawYaml): String =
    renderBlock(content, indent = 0).mkString("\n") + "\n"

  def describe(spec: CommandSpec): String =
    spec.redacted.invocation match
      case RedactedCommandInvocation.Direct(argv) =>
        argv.mkString(" ")
      case RedactedCommandInvocation.Shell(command, shell) =>
        (shell :+ command).mkString(" ")

  private def toolCommand(tool: ToolInvocation, extraArgs: Vector[String]): CommandSpec =
    CommandSpec.direct(
      argv = (Vector(tool.path) ++ tool.args ++ extraArgs).map(CommandArgument(_)),
      sudo = SudoMode.Disabled
    )

  private def configDryRunActions(config: GeneratedConfig): Vector[DryRunAction] =
    if config.create.contains(true) then
      Vector(DryRunAction.FileWrite(config.path, mode = None, description = "generate Nerd Fonts config"))
    else Vector.empty

  private def dryRunCommand(command: CommandSpec): DryRunAction =
    command.redacted.invocation match
      case RedactedCommandInvocation.Direct(argv) =>
        DryRunAction.Command(
          argv = argv,
          shell = None,
          sudo = command.sudo == SudoMode.Required,
          workingDirectory = command.cwd.map(_.toString)
        )
      case RedactedCommandInvocation.Shell(commandText, shell) =>
        DryRunAction.Command(
          argv = Vector(commandText),
          shell = Some(shell.mkString(" ")),
          sudo = command.sudo == SudoMode.Required,
          workingDirectory = command.cwd.map(_.toString)
        )

  private def renderBlock(value: RawYaml, indent: Int): Vector[String] =
    value match
      case RawYaml.MappingValue(fields) if fields.isEmpty =>
        Vector(spaces(indent) + "{}")
      case RawYaml.MappingValue(fields) =>
        fields.toVector.flatMap((key, child) => renderMappingEntry(key, child, indent))
      case RawYaml.SequenceValue(items) if items.isEmpty =>
        Vector(spaces(indent) + "[]")
      case RawYaml.SequenceValue(items) =>
        items.flatMap(item => renderSequenceItem(item, indent))
      case scalar =>
        Vector(spaces(indent) + renderScalar(scalar))

  private def renderMappingEntry(key: String, value: RawYaml, indent: Int): Vector[String] =
    value match
      case scalar if isScalar(scalar) =>
        Vector(s"${spaces(indent)}$key: ${renderScalar(scalar)}")
      case nested =>
        Vector(s"${spaces(indent)}$key:") ++ renderBlock(nested, indent + 2)

  private def renderSequenceItem(value: RawYaml, indent: Int): Vector[String] =
    value match
      case scalar if isScalar(scalar) =>
        Vector(s"${spaces(indent)}- ${renderScalar(scalar)}")
      case nested =>
        Vector(s"${spaces(indent)}-") ++ renderBlock(nested, indent + 2)

  private def isScalar(value: RawYaml): Boolean =
    value match
      case RawYaml.MappingValue(_)  => false
      case RawYaml.SequenceValue(_) => false
      case _                        => true

  private def renderScalar(value: RawYaml): String =
    value match
      case RawYaml.NullValue          => "null"
      case RawYaml.StringValue(text)  => quote(text)
      case RawYaml.BooleanValue(flag) => flag.toString
      case RawYaml.IntegerValue(int)  => int.toString
      case RawYaml.DecimalValue(num)  => num.toString
      case RawYaml.MappingValue(_)    => "{}"
      case RawYaml.SequenceValue(_)   => "[]"

  private def quote(value: String): String =
    "'" + value.replace("'", "''") + "'"

  private def spaces(count: Int): String =
    " " * count
