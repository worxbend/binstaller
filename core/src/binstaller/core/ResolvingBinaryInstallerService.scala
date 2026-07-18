package binstaller.core

import binstaller.config.ConfigModule

import java.nio.file.Path

private[core] final case class VersionSummaryRow(
    packageName: String,
    version: String,
    newerVersion: String
)

private[core] final case class InstallerRunStatistics(installed: Int, failed: Int, skipped: Int)

private[core] object InstallerRunStatistics:

  def fromResult(result: InstallerResult): InstallerRunStatistics = InstallerRunStatistics(
    installed = result.terminalResults.count(_.isInstanceOf[TerminalToolResult.Completed]),
    failed = result.terminalResults.count(_.isInstanceOf[TerminalToolResult.Failed]),
    skipped = result.skippedTools
  )

private[core] final class ResolvingBinaryInstallerService(
    httpTextClient: HttpTextClient,
    resolutionOptions: ResolutionOptions,
    installer: DirectBinaryInstaller,
    stateStore: ApplyStateStore,
    metadataClient: BinaryMetadataClient,
    lockFileStore: LockFileStore
) extends BinaryInstallerService:

  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    val eventContext = InstallerEventContext.start(eventObserver)
    renderSelectedPlanWithEvents(options, eventContext)

  override def applyWithProgress(
      options: InstallerOptions,
      progressObserver: BinaryDownloadProgressObserver
  ): InstallerResult =
    applyWithEvents(options, InstallerEventObserver.fromDownloadProgress(progressObserver))

  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    val eventContext = InstallerEventContext.start(eventObserver)
    eventContext.emit(InstallerEvent.ResolvingStarted(options.configPath, _))
    resolveSelectedPreparedPlan(options) match
      case Left(error) =>
        val result = renderError(error)
        emitSummary(result, stateFilePath = None, eventContext)
        result
      case Right(prepared) => validateLockIfRequested(options, prepared) match
          case Left(error) =>
            val result = renderLockedApplyError(error)
            emitSummary(result, stateFilePath = None, eventContext)
            result
          case Right(lockedProvenance) =>
            val lockedPrepared = lockedProvenance.fold(prepared)(applyLockedChecksums(prepared, _))
            val statePath      = configuredStatePath(options, lockedPrepared.plan)
            eventContext.emit(InstallerEvent.PlanReady(
              lockedPrepared.plan.tools.map(_.name),
              statePath,
              _
            ))
            val result =
              StatefulApplyRunner.run(options, lockedPrepared, installer, stateStore, eventContext)
            emitSummary(result, statePath, eventContext)
            result

  def versions(options: InstallerOptions): InstallerResult =
    resolveFromOptions(options).fold(renderError, renderVersions)

  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult =
    resolveSelectedPreparedPlan(options) match
      case Left(error)     => renderError(error)
      case Right(prepared) => LockFileBuilder.build(prepared, metadataClient) match
          case Left(error) => InstallerResult(
              Vector(s"lock inspection failed for tool '${error.toolName}': ${error.message}"),
              1
            )
          case Right(lockFile) =>
            val path = Path.of(lockOptions.outputPath)
            lockFileStore.save(path, lockFile) match
              case Right(()) => InstallerResult(
                  Vector(
                    s"wrote lock file: ${path.toAbsolutePath.normalize()}",
                    s"profile: ${prepared.profileName}",
                    s"manifest fingerprint: ${prepared.manifestFingerprint}",
                    s"tools: ${lockFile.tools.size}",
                    s"checksums: ${LockFileChecksum.summary(lockFile.tools)}"
                  ),
                  0
                )
              case Left(error) => InstallerResult(Vector(LockFileError.render(error)), 1)

  private def renderSelectedPlanWithEvents(
      options: InstallerOptions,
      eventContext: InstallerEventContext
  ): InstallerResult =
    eventContext.emit(InstallerEvent.ResolvingStarted(options.configPath, _))
    resolveSelectedPreparedPlan(options) match
      case Left(error) =>
        val result = renderError(error)
        emitSummary(result, stateFilePath = None, eventContext)
        result
      case Right(prepared) =>
        val plan      = prepared.plan
        val statePath = configuredStatePath(options, plan)
        validateLockIfRequested(options, prepared) match
          case Left(error) =>
            val result = renderLockedApplyError(error)
            emitSummary(result, stateFilePath = None, eventContext)
            result
          case Right(lockedProvenance) =>
            eventContext.emit(InstallerEvent.PlanReady(plan.tools.map(_.name), statePath, _))
            val result = PlanRenderer.render(plan, lockedProvenance)
            emitSummary(result, statePath, eventContext)
            result

  private def resolveSelectedPreparedPlan(
      options: InstallerOptions
  ): Either[ResolvePlanError, PreparedPlan] = resolveFromOptions(options).flatMap: prepared =>
    ToolSelector.select(prepared.plan, options.selection).map: selected =>
      prepared.copy(plan = selected)

  private def resolveFromOptions(
      options: InstallerOptions
  ): Either[ResolvePlanError, PreparedPlan] = ConfigModule.load(options.configPath) match
    case Left(error)    => Left(ResolvePlanError.ConfigLoadFailed(error))
    case Right(profile) => PlanResolver.resolve(profile, resolutionOptions, httpTextClient).map:
        plan =>
          PreparedPlan(
            profile,
            profile.metadata.name,
            ManifestFingerprint.profile(profile),
            plan
          )

  private def configuredStatePath(
      options: InstallerOptions,
      plan: ResolvedPlan
  ): Option[String] = options.statePath.orElse(plan.policy.stateFile)

  private def emitSummary(
      result: InstallerResult,
      stateFilePath: Option[String],
      eventContext: InstallerEventContext
  ): Unit =
    val status =
      if result.exitCode == 0 then InstallerRunStatus.Succeeded
      else InstallerRunStatus.Failed
    val statistics = InstallerRunStatistics.fromResult(result)
    eventContext.emit(InstallerEvent.Summary(
      status,
      installed = statistics.installed,
      failed = statistics.failed,
      skipped = statistics.skipped,
      exitCode = result.exitCode,
      stateFilePath = stateFilePath,
      _
    ))

  private def renderVersions(prepared: PreparedPlan): InstallerResult =
    val newerVersions = GitHubReleaseVersions.newerVersionsByTool(prepared.plan, httpTextClient)
    val rows          = prepared.plan.tools.map: tool =>
      VersionSummaryRow(
        packageName = tool.name,
        version = ResolvedVersion.render(tool.version),
        newerVersion = newerVersions.get(tool.name).getOrElse("-")
      )
    val lines = renderVersionSummaryTable(rows)
    InstallerResult(
      RenderSafety.displayLines("binstaller versions" +: lines, prepared.plan.redactions),
      0
    )

  private def renderVersionSummaryTable(rows: Vector[VersionSummaryRow]): Vector[String] =
    val headers      = VersionSummaryRow("package", "version", "newer version")
    val displayRows  = headers +: rows
    val packageWidth = displayRows.map(_.packageName.length).max
    val versionWidth = displayRows.map(_.version.length).max
    displayRows.map: row =>
      s"${row.packageName.padTo(packageWidth, ' ')}  " +
        s"${row.version.padTo(versionWidth, ' ')}  " +
        row.newerVersion

  private def renderError(error: ResolvePlanError): InstallerResult =
    InstallerResult(ResolvePlanError.renderLines(error), 1)

  private def validateLockIfRequested(
      options: InstallerOptions,
      prepared: PreparedPlan
  ): Either[LockedApplyError, Option[LockedApplyProvenance]] = options.lockedApply match
    case LockedApplyMode.Disabled => Right(None)
    case LockedApplyMode.Enabled  => LockedApplyValidator
        .validate(prepared, Path.of(options.lockPath), lockFileStore, metadataClient)
        .map(Some(_))

  private def renderLockedApplyError(error: LockedApplyError): InstallerResult =
    InstallerResult(LockedApplyError.renderLines(error), 1)

  private def applyLockedChecksums(
      prepared: PreparedPlan,
      locked: LockedApplyProvenance
  ): PreparedPlan = prepared.copy(plan = prepared.plan.copy(tools = prepared.plan.tools.map: tool =>
    locked.tools.get(tool.name).flatMap(_.checksum) match
      case Some(checksum) => tool.copy(download =
          tool.download.copy(checksum =
            Some(
              ResolvedChecksum(
                binstaller.config.ChecksumAlgorithm.Sha256,
                checksum.value,
                ResolvedChecksumSource.Configured
              )
            )
          )
        )
      case None => tool))
