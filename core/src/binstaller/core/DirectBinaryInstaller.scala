package binstaller.core

import binstaller.config.PolicyOverride
import binstaller.config.ChecksumAlgorithm
import binstaller.config.Diagnostics
import binstaller.config.SymlinkPrivilege

import java.nio.file.Path
import java.time.Duration
import ox.channels.BufferCapacity
import ox.flow.Flow
import ox.supervised

private[core] final case class ObservedInstallResults(
    lines: Vector[String],
    results: Vector[TerminalToolResult],
    persistenceError: Option[String]
)

private[core] enum PreparedToolResult:

  case Ready(
      tool: ResolvedTool,
      stagedInstall: StagedInstall,
      download: UrlProvenance,
      verboseLines: Vector[String]
  )

  case Failed(
      toolName: String,
      error: ToolInstallError,
      verboseLines: Vector[String]
  )

/** Installer that applies resolved direct-binary and archive-backed tools. */
final class DirectBinaryInstaller(
    downloadClient: BinaryDownloadClient,
    private[core] val fileSystem: InstallFileSystem,
    commandExecutor: CommandExecutor = CommandExecutor.process,
    sudoCredentials: SudoCredentialProvider = SudoCredentialProvider.unavailable
):

  /** Install every tool in an already-resolved plan and render terminal result lines.
   *
   * Core-internal (tests/helpers): it consumes a [[ResolvedPlan]] directly and therefore skips the
   * PlanResolver appsDir-containment validation that the production ingest path enforces. Not part
   * of the public boundary — go through [[BinaryInstallerService]] instead.
   */
  private[core] def installPlan(
      plan: ResolvedPlan,
      verboseOutput: VerboseOutput = VerboseOutput.Disabled,
      progressObserver: BinaryDownloadProgressObserver = BinaryDownloadProgressObserver.none
  ): InstallerResult = installPlanWithObserver(
    plan,
    verboseOutput,
    _ => Right(()),
    InstallerEventContext.start(InstallerEventObserver.fromDownloadProgress(progressObserver)),
    ApplyParallelism.default
  )

  private[core] def installPlanWithObserver(
      plan: ResolvedPlan,
      verboseOutput: VerboseOutput,
      terminalObserver: TerminalToolResult => Either[String, Unit],
      eventContext: InstallerEventContext,
      applyParallelism: ApplyParallelism = ApplyParallelism.default
  ): InstallerResult = preflight(plan) match
    case Some(error) =>
      InstallerResult(Vector(ApplyPreflightError.render(error)), InstallerRunStatus.Failed)
    case None        =>
      val observed = installTools(
        plan.policy,
        plan.tools,
        plan.redactions,
        verboseOutput,
        terminalObserver,
        eventContext,
        applyParallelism
      )
      val lines = observed.lines ++
        observed.persistenceError.map(message => s"state write failed: $message").toVector
      val status =
        if observed.results.exists(_.isInstanceOf[TerminalToolResult.Failed]) ||
          observed.persistenceError.nonEmpty
        then InstallerRunStatus.Failed
        else InstallerRunStatus.Succeeded

      InstallerResult(
        lines,
        status,
        terminalResults = observed.results,
        // Rendered with the same redactions used to build `lines`, so these texts are the very
        // strings that appear there -- a renderer can match on them rather than parse them.
        renderedTerminalLines =
          observed.results.map(TerminalToolResult.renderedLine(_, plan.redactions))
      )

  private def preflight(plan: ResolvedPlan): Option[ApplyPreflightError] = plan.tools
    .find(_.symlinks.exists(_.privilege == SymlinkPrivilege.Sudo))
    .flatMap: tool =>
      plan.policy.allowSudoSymlinks match
        case PolicyOverride.Disabled =>
          Some(ApplyPreflightError.SudoSymlinkNotAllowed(tool.name))
        case PolicyOverride.Enabled => None

  private def installTools(
      policy: ResolvedPolicy,
      tools: Vector[ResolvedTool],
      redactions: SensitiveValueRedactions,
      verboseOutput: VerboseOutput,
      terminalObserver: TerminalToolResult => Either[String, Unit],
      eventContext: InstallerEventContext,
      applyParallelism: ApplyParallelism
  ): ObservedInstallResults =
    if tools.isEmpty then ObservedInstallResults(Vector.empty, Vector.empty, None)
    else
      supervised:
        given BufferCapacity = BufferCapacity(applyParallelism.value)
        val serializedEvents     = eventContext.serialized
        val preparedResults      = Flow
          .fromIterable(tools)
          .mapPar(applyParallelism.value): tool =>
            prepareTool(tool, redactions, verboseOutput, serializedEvents)
          .runToList()
          .toVector
        finalizePreparedResults(
          policy,
          preparedResults,
          redactions,
          terminalObserver,
          serializedEvents
        )

  /** Install a single tool without sudo symlink support. Core-internal (tests/helpers): it takes a
   *  [[ResolvedTool]] directly and so bypasses the PlanResolver appsDir-containment validation the
   *  production path enforces; not part of the public boundary. */
  private[core] def installTool(
      tool: ResolvedTool
  ): Either[ToolInstallError, TerminalToolResult.Completed] =
    val policy = ResolvedPolicy.restricted(tool.installDir)
    if tool.symlinks.exists(_.privilege == SymlinkPrivilege.Sudo) then
      Left(ToolInstallError.SudoSymlinkNotAllowed(tool.name))
    else
      installTool(
        policy,
        tool,
        InstallerEventContext.start(InstallerEventObserver.none),
        SensitiveValueRedactions.empty
      )

  private def installTool(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): Either[ToolInstallError, TerminalToolResult.Completed] =
    installDownloadedBinaryOrArchive(policy, tool, eventContext, redactions)

  private def terminalResult(
      result: Either[ToolInstallError, TerminalToolResult.Completed],
      redactions: SensitiveValueRedactions
  ): TerminalToolResult = result.fold(
    error => TerminalToolResult.Failed(
      error.toolName,
      ToolInstallError.render(error, redactions)
    ),
    identity
  )

  private def toolResultEvent(
      result: TerminalToolResult
  )(elapsedTime: Duration): InstallerEvent = result match
    case TerminalToolResult.Completed(toolName, installDir, _) => InstallerEvent.ToolResult(
        toolName,
        ToolResultStatus.Completed,
        Some(installDir),
        None,
        elapsedTime
      )
    case TerminalToolResult.Failed(toolName, message) => InstallerEvent.ToolResult(
        toolName,
        ToolResultStatus.Failed,
        None,
        Some(rootCauseSummary(message)),
        elapsedTime
      )

  private def rootCauseSummary(message: String): String =
    message.linesIterator.nextOption.getOrElse(message)

  private def verboseLines(
      tool: ResolvedTool,
      verboseOutput: VerboseOutput,
      redactions: SensitiveValueRedactions
  ): Vector[String] = verboseOutput match
    case VerboseOutput.Disabled => Vector.empty
    case VerboseOutput.Enabled  =>
      val downloadLine   = s"verbose ${tool.name}: download ${tool.download.url}"
      val extractionLine = tool.download.archive match
        case Some(archive) => Some(
            s"verbose ${tool.name}: extract ${archive.original.archiveType.value} ${tool.download.filename}"
          )
        case None => None
      RenderSafety.displayLines(Vector(downloadLine) ++ extractionLine.toVector, redactions)

  // The synchronous path is exactly the parallel prepare + finalize pipeline run inline: prepare
  // downloads/verifies/stages (and discards the artifact + staging on its own failure), then
  // completePreparedTool replaces/verifies/links. Composing them keeps the two paths in lockstep.
  private def installDownloadedBinaryOrArchive(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): Either[ToolInstallError, TerminalToolResult.Completed] =
    prepareDownloadedBinaryOrArchive(tool, eventContext, redactions).flatMap:
      case (staged, provenance) => completePreparedTool(policy, tool, staged, provenance, eventContext)

  private def prepareTool(
      tool: ResolvedTool,
      redactions: SensitiveValueRedactions,
      verboseOutput: VerboseOutput,
      eventContext: InstallerEventContext
  ): PreparedToolResult =
    val verbose = verboseLines(tool, verboseOutput, redactions)
    // Keep this total: a thrown fork aborts Flow.runToList before finalize runs, so sibling staged
    // installs would never be discarded. Converting a throw into a Failed result preserves cleanup.
    try
      verbose.foreach(line =>
        eventContext.emit(InstallerEvent.LogLine(Some(tool.name), line, _))
      )
      eventContext.emit(InstallerEvent.ToolStarted(tool.name, InstallerPhase.Downloading, _))
      prepareDownloadedBinaryOrArchive(tool, eventContext, redactions) match
        case Right((stagedInstall, provenance)) =>
          PreparedToolResult.Ready(tool, stagedInstall, provenance, verbose)
        case Left(error) => PreparedToolResult.Failed(tool.name, error, verbose)
    catch
      case scala.util.control.NonFatal(error) =>
        val message = Diagnostics.describe(error)
        PreparedToolResult.Failed(tool.name, ToolInstallError.StagingFailed(tool.name, message), verbose)

  private def prepareDownloadedBinaryOrArchive(
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): Either[ToolInstallError, (StagedInstall, UrlProvenance)] =
    download(tool, eventContext, redactions).flatMap: artifact =>
      // The downloaded temp file must be deleted on every exit, including a throw out of staging or
      // out of an event observer. prepareTool deliberately catches NonFatal and turns such a throw
      // into a Failed result, so without `finally` the throw would skip the discard and silently
      // leave behind a file of up to the download size cap.
      try
        for
          // Integrity is checked before staging/replacement so a bad artifact cannot overwrite a
          // previously working install.
          _ <- withPhase(tool, InstallerPhase.VerifyingChecksum, eventContext)(
            verifyChecksum(tool, artifact.sha256)
          )
          staged <-
            withPhase(tool, InstallerPhase.Staging, eventContext)(stage(tool, artifact.path))
          _ <- prepareStagedInstall(tool, staged, eventContext)
        yield staged -> artifact.provenance
      finally artifact.discard()

  private def prepareStagedInstall(
      tool: ResolvedTool,
      stagedInstall: StagedInstall,
      eventContext: InstallerEventContext
  ): Either[ToolInstallError, Unit] =
    val result =
      for
        _ <- withPhase(tool, InstallerPhase.VerifyingExecutables, eventContext)(
          verifyExecutablesUnder(tool, stagedInstall.stagingDir)
        )
        _ <- withPhase(tool, InstallerPhase.ApplyingModes, eventContext)(
          applyModes(tool, stagedInstall)
        )
      yield ()

    result.left.map: error =>
      fileSystem.discardStaged(stagedInstall)
      error

  private def finalizePreparedResults(
      policy: ResolvedPolicy,
      preparedResults: Vector[PreparedToolResult],
      redactions: SensitiveValueRedactions,
      terminalObserver: TerminalToolResult => Either[String, Unit],
      eventContext: InstallerEventContext
  ): ObservedInstallResults =
    val initial = ObservedInstallResults(Vector.empty, Vector.empty, None)
    preparedResults.foldLeft(initial): (observed, prepared) =>
      if observed.persistenceError.nonEmpty ||
        stoppedAfterFailure(policy, observed.results)
      then
        discardPrepared(prepared)
        observed
      else
        appendFinalizedResult(
          observed,
          finalizePrepared(policy, prepared, eventContext),
          redactions,
          terminalObserver,
          eventContext
        )

  private def stoppedAfterFailure(
      policy: ResolvedPolicy,
      results: Vector[TerminalToolResult]
  ): Boolean = policy.continueOnError == PolicyOverride.Disabled &&
    results.exists(_.isInstanceOf[TerminalToolResult.Failed])

  private def discardPrepared(prepared: PreparedToolResult): Unit = prepared match
    case PreparedToolResult.Ready(_, stagedInstall, _, _) => fileSystem.discardStaged(stagedInstall)
    case PreparedToolResult.Failed(_, _, _)               => ()

  private def finalizePrepared(
      policy: ResolvedPolicy,
      prepared: PreparedToolResult,
      eventContext: InstallerEventContext
  ): (Vector[String], Either[ToolInstallError, TerminalToolResult.Completed]) = prepared match
    case PreparedToolResult.Failed(_, error, verbose)                     => verbose -> Left(error)
    case PreparedToolResult.Ready(tool, stagedInstall, download, verbose) => verbose ->
        completePreparedTool(policy, tool, stagedInstall, download, eventContext)

  private def completePreparedTool(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      stagedInstall: StagedInstall,
      download: UrlProvenance,
      eventContext: InstallerEventContext
  ): Either[ToolInstallError, TerminalToolResult.Completed] =
    for
      _ <-
        withPhase(tool, InstallerPhase.ReplacingInstall, eventContext)(replace(tool, stagedInstall))
      _ <- withPhase(tool, InstallerPhase.VerifyingExecutables, eventContext)(
        verifyExecutablesUnder(tool, Path.of(tool.installDir))
      )
      _ <- withPhase(tool, InstallerPhase.CreatingSymlinks, eventContext)(
        SymlinkInstaller.create(policy, tool, commandExecutor, sudoCredentials)
      )
    yield TerminalToolResult.Completed(tool.name, tool.installDir, Some(download))

  private def appendFinalizedResult(
      observed: ObservedInstallResults,
      finalized: (Vector[String], Either[ToolInstallError, TerminalToolResult.Completed]),
      redactions: SensitiveValueRedactions,
      terminalObserver: TerminalToolResult => Either[String, Unit],
      eventContext: InstallerEventContext
  ): ObservedInstallResults =
    val (verbose, result) = finalized
    val terminal          = terminalResult(result, redactions)
    eventContext.emit(toolResultEvent(terminal))
    val terminalLines = Vector(TerminalToolResult.line(terminal, redactions))
    terminalObserver(terminal) match
      case Left(message) => observed.copy(
          lines = observed.lines ++ verbose ++ terminalLines,
          results = observed.results :+ terminal,
          persistenceError = Some(RenderSafety.display(message, redactions))
        )
      case Right(()) => observed.copy(
          lines = observed.lines ++ verbose ++ terminalLines,
          results = observed.results :+ terminal
        )

  private def download(
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): Either[ToolInstallError, BinaryDownloadArtifact] =
    downloadClient.downloadArtifactWithProvenance(
      tool.download.url,
      downloadProgressObserver(tool, eventContext, redactions)
    ).left.map: error =>
      ToolInstallError.DownloadFailed(
        tool.name,
        RenderSafety.display(error.url, redactions),
        RenderSafety.display(error.message, redactions),
        error.provenance
      )

  private def downloadProgressObserver(
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): BinaryDownloadProgressObserver = new BinaryDownloadProgressObserver:
    def onProgress(progress: BinaryDownloadProgress): Unit =
      val (url, downloadedBytes, totalBytes, status) = progressSnapshot(progress)
      eventContext.emit(InstallerEvent.DownloadProgress(
        tool.name,
        RenderSafety.display(url, redactions),
        downloadedBytes,
        totalBytes,
        status,
        _
      ))

  /** The one thing that actually differs between the three download progress stages. */
  private def progressSnapshot(
      progress: BinaryDownloadProgress
  ): (String, Long, Option[Long], DownloadProgressStatus) = progress match
    case BinaryDownloadProgress.Started(url, totalBytes) =>
      (url, 0L, totalBytes, DownloadProgressStatus.Started)
    case BinaryDownloadProgress.Advanced(url, downloadedBytes, totalBytes) =>
      (url, downloadedBytes, totalBytes, DownloadProgressStatus.Advanced)
    case BinaryDownloadProgress.Finished(url, downloadedBytes, totalBytes) =>
      (url, downloadedBytes, totalBytes, DownloadProgressStatus.Finished)

  private def withPhase[A](
      tool: ResolvedTool,
      phase: InstallerPhase,
      eventContext: InstallerEventContext
  )(result: => Either[ToolInstallError, A]): Either[ToolInstallError, A] =
    eventContext.emit(InstallerEvent.ToolPhaseChanged(tool.name, phase, _))
    result

  private def verifyChecksum(
      tool: ResolvedTool,
      actual: Sha256Digest
  ): Either[ToolInstallError, Unit] = tool.download.checksum match
    case None           => Right(())
    case Some(checksum) => checksum.algorithm match
        case ChecksumAlgorithm.Sha256 =>
          if actual.value.equalsIgnoreCase(checksum.value) then Right(())
          else
            Left(ToolInstallError.ChecksumMismatch(
              tool.name,
              checksum.value,
              actual.value,
              ResolvedChecksum.sourceDescription(checksum)
            ))

  private def stage(
      tool: ResolvedTool,
      artifact: Path
  ): Either[ToolInstallError, StagedInstall] = tool.download.archive match
    case Some(archive) => fileSystem
        .stageArchiveFromFile(
          Path.of(tool.installDir),
          tool.createDirectories,
          archive,
          artifact
        )
        .left
        .map(error => ToolInstallError.ArchiveExtractionFailed(tool.name, error.message))
    case None => tool.executables.headOption match
        case None                  => Left(ToolInstallError.MissingExecutable(tool.name, "<none>"))
        case Some(firstExecutable) => fileSystem
            .stageDirectBinaryFromFile(
              Path.of(tool.installDir),
              tool.createDirectories,
              firstExecutable.path,
              artifact
            )
            .left
            .map(error => ToolInstallError.StagingFailed(tool.name, error.message))

  private def applyModes(
      tool: ResolvedTool,
      stagedInstall: StagedInstall
  ): Either[ToolInstallError, Unit] =
    val modes = tool.executables.map: executable =>
      ExecutableModeRequest(executable.path, ExecutableInstallMode.fromConfig(executable.mode))

    fileSystem.applyExecutableModes(stagedInstall, modes).left.map: error =>
      ToolInstallError.ModeApplicationFailed(tool.name, error.path, error.mode, error.message)

  private def replace(
      tool: ResolvedTool,
      stagedInstall: StagedInstall
  ): Either[ToolInstallError, Unit] = fileSystem.replaceInstall(stagedInstall).left.map: error =>
    ToolInstallError.ReplacementFailed(tool.name, error.message)

  /** Fails on the first declared executable that is not a regular file under `root`.
   *
   *  One rule, two roots: the staging tree before the install is swapped in, and the final install
   *  directory afterwards. Previously each root had its own copy of the rule plus its own path
   *  resolver, so a change to what counts as a valid executable had to be made in four places.
   *
   *  `.iterator` keeps the scan lazy, so it stops at the first failure rather than stat-ing every
   *  remaining path.
   */
  private def verifyExecutablesUnder(
      tool: ResolvedTool,
      root: Path
  ): Either[ToolInstallError, Unit] = tool.executables.iterator
    .map: executable =>
      SafePaths
        .resolveInside(root, executable.path)
        .left.map(message => ToolInstallError.StagingFailed(tool.name, message))
        .flatMap: path =>
          if fileSystem.isRegularFile(path) then Right(())
          else Left(ToolInstallError.MissingExecutable(tool.name, executable.path))
    .collectFirst:
      case Left(error) => error
    .toLeft(())

object DirectBinaryInstaller:

  /** Production installer wired to JDK downloads, NIO staging, and bounded process execution. */
  def default: DirectBinaryInstaller =
    DirectBinaryInstaller(BinaryDownloadClient.jdk, NioInstallFileSystem, CommandExecutor.process)

  /** Production installer wired with an explicit sudo credential boundary. */
  def default(sudoCredentials: SudoCredentialProvider): DirectBinaryInstaller =
    DirectBinaryInstaller(
      BinaryDownloadClient.jdk,
      NioInstallFileSystem,
      CommandExecutor.process,
      sudoCredentials
    )
