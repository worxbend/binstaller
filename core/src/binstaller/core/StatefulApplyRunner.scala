package binstaller.core

import binstaller.config.ToolName

import java.nio.file.Path

private[core] object StatefulApplyRunner:

  // Loading the state file is not a tool, but the phase event is keyed by tool name. This label
  // stands in for it rather than the event contract growing a second shape for one case.
  private val stateLoadingLabel: ToolName = ToolName.unsafe("state")

  def run(
      options: InstallerOptions,
      prepared: PreparedPlan,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore,
      eventContext: InstallerEventContext
  ): InstallerResult = statePath(options, prepared.plan) match
    case None => installer.installPlanWithObserver(
        prepared.plan,
        options.verboseOutput,
        _ => Right(()),
        eventContext,
        options.applyParallelism
      )
    case Some(path) =>
      eventContext.emit(InstallerEvent.LogLine(
        None,
        RenderSafety.display(s"state file: $path", prepared.plan.redactions),
        _
      ))
      eventContext.emit(InstallerEvent.ToolPhaseChanged(
        StatefulApplyRunner.stateLoadingLabel,
        InstallerPhase.LoadingState,
        _
      ))
      loadInitialState(path, options.resetState, prepared, stateStore) match
        case Left(error) => InstallerResult(
            Vector(RenderSafety.display(
              ApplyStateError.render(error),
              prepared.plan.redactions
            )),
            InstallerRunStatus.Failed
          )
        case Right((statePath, state)) => runWithState(
            statePath,
            state,
            prepared,
            options,
            installer,
            stateStore,
            eventContext
          )

  private def statePath(options: InstallerOptions, plan: ResolvedPlan): Option[String] =
    options.statePath.orElse(plan.policy.stateFile)

  private def loadInitialState(
      rawPath: String,
      resetState: ResetState,
      prepared: PreparedPlan,
      stateStore: ApplyStateStore
  ): Either[ApplyStateError, (Path, ApplyState)] =
    for
      // State files are intentionally CWD-local filenames only; this prevents a profile or CLI
      // option from writing outside the working directory or targeting an install path.
      path  <- StatePathResolver.resolve(rawPath, stateStore.cwd)
      state <- resetState match
        case ResetState.Enabled => Right(
            ApplyState.empty(prepared.profileName, prepared.manifestFingerprint)
          )
        case ResetState.Disabled => stateStore.load(path).flatMap:
            case None => Right(ApplyState.empty(prepared.profileName, prepared.manifestFingerprint))
            case Some(state) => validateState(path, state, prepared)
    yield path -> state

  private def validateState(
      path: Path,
      state: ApplyState,
      prepared: PreparedPlan
  ): Either[ApplyStateError, ApplyState] =
    if state.schemaVersion != ApplyState.schemaVersion then
      Left(ApplyStateError.UnsupportedSchema(path, ApplyState.schemaVersion, state.schemaVersion))
    else if state.profileName == prepared.profileName &&
      state.manifestFingerprint == prepared.manifestFingerprint
    then Right(state)
    else
      Left(
        ApplyStateError.IncompatibleState(
          path,
          prepared.profileName,
          state.profileName,
          prepared.manifestFingerprint,
          state.manifestFingerprint
        )
      )

  private def runWithState(
      path: Path,
      state: ApplyState,
      prepared: PreparedPlan,
      options: InstallerOptions,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore,
      eventContext: InstallerEventContext
  ): InstallerResult =
    // The installer already owns the filesystem it installs through; taking a second one as a
    // parameter meant two names for the same collaborator with nothing keeping them in agreement.
    val fileSystem = installer.fileSystem
    val completed = prepared.plan.tools.filter(tool => completedAndPresent(state, tool, fileSystem))
      .map(_.name)
      .toSet
    val pendingTools = prepared.plan.tools.filterNot(tool => completed(tool.name))
    val skippedLines = prepared.plan.tools
      .filter(tool => completed(tool.name))
      .map: tool =>
        eventContext.emit(InstallerEvent.ToolSkipped(
          tool.name,
          "already completed in state",
          Some(path.toString),
          _
        ))
        s"skipped ${tool.name}: already completed in state"
    val pendingPlan  = prepared.plan.copy(tools = pendingTools)
    var currentState = state
    val terminalObserver: TerminalToolResult => Either[String, Unit] = terminal =>
      eventContext.emit(InstallerEvent.ToolPhaseChanged(
        toolName(terminal),
        InstallerPhase.SavingState,
        _
      ))
      currentState = updateState(currentState, terminal)
      stateStore.save(path, currentState).left.map(error =>
        RenderSafety.display(ApplyStateError.render(error), prepared.plan.redactions)
      )
    val result = installer.installPlanWithObserver(
      pendingPlan,
      options.verboseOutput,
      terminalObserver,
      eventContext,
      options.applyParallelism
    )

    result.copy(lines = skippedLines ++ result.lines, skippedTools = skippedLines.size)

  /**
   * Whether a tool recorded as completed is still actually installed.
   *
   * This is a business rule — "may apply skip this tool?" — so it asks the injected
   * [[InstallFileSystem]] rather than `java.nio.file.Files` directly. Probing the real disk here
   * meant a test supplying an in-memory filesystem still had its skip decision made by whatever
   * happened to exist on the machine running the test.
   */
  private def completedAndPresent(
      state: ApplyState,
      tool: ResolvedTool,
      fileSystem: InstallFileSystem
  ): Boolean = state.tools
    .find(_.name == tool.name)
    .exists: saved =>
      saved.status == ApplyStateToolStatus.Completed &&
        saved.installDir.contains(tool.installDir) &&
        tool.executables.forall(executable =>
          installedFileExists(tool, executable.path, fileSystem)
        ) &&
        tool.symlinks.forall(symlink => installedSymlinkMatches(tool, symlink, fileSystem))

  private def installedFileExists(
      tool: ResolvedTool,
      relative: String,
      fileSystem: InstallFileSystem
  ): Boolean =
    val root     = Path.of(tool.installDir).toAbsolutePath.normalize()
    val resolved = root.resolve(relative).normalize()
    resolved.startsWith(root) && fileSystem.isRegularFile(resolved)

  private def installedSymlinkMatches(
      tool: ResolvedTool,
      symlink: ResolvedSymlink,
      fileSystem: InstallFileSystem
  ): Boolean =
    val installRoot = Path.of(tool.installDir).toAbsolutePath.normalize()
    val rawPath     = Path.of(symlink.path)
    val path        =
      if rawPath.isAbsolute then rawPath.normalize() else installRoot.resolve(rawPath).normalize()
    val rawTarget = Path.of(symlink.target)
    val expected  =
      if rawTarget.isAbsolute then rawTarget.normalize()
      else installRoot.resolve(rawTarget).normalize()
    // A path that is not a symlink and one whose target cannot be read both yield None, which is
    // the same "does not match" answer the explicit guard plus Try(...).getOrElse(false) gave.
    fileSystem.symlinkTarget(path).exists: actualRaw =>
      val actual =
        if actualRaw.isAbsolute then actualRaw.normalize()
        else Option(path.getParent).getOrElse(installRoot).resolve(actualRaw).normalize()
      actual == expected

  private def updateState(state: ApplyState, result: TerminalToolResult): ApplyState =
    val updatedTool = result match
      case TerminalToolResult.Completed(toolName, installDir, download) => ApplyStateTool(
          toolName,
          ApplyStateToolStatus.Completed,
          Some(installDir),
          None,
          download
        )
      case TerminalToolResult.Failed(toolName, message) =>
        ApplyStateTool(toolName, ApplyStateToolStatus.Failed, None, Some(message))
    state.copy(tools = replaceTool(state.tools, updatedTool))

  private def toolName(result: TerminalToolResult): ToolName = result match
    case TerminalToolResult.Completed(toolName, _, _) => toolName
    case TerminalToolResult.Failed(toolName, _)       => toolName

  private def replaceTool(
      tools: Vector[ApplyStateTool],
      updated: ApplyStateTool
  ): Vector[ApplyStateTool] =
    val withoutCurrent = tools.filterNot(_.name == updated.name)
    withoutCurrent :+ updated
