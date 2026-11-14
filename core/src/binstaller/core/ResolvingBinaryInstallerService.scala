package binstaller.core

import binstaller.config.ChecksumAlgorithm
import binstaller.config.Sha256Digest

import java.nio.file.Path

private[core] final case class InstallerRunStatistics(installed: Int, failed: Int, skipped: Int)

private[core] object InstallerRunStatistics:

  def fromResult(result: InstallerResult): InstallerRunStatistics = InstallerRunStatistics(
    installed = result.terminalResults.count(_.isInstanceOf[TerminalToolResult.Completed]),
    failed = result.terminalResults.count(_.isInstanceOf[TerminalToolResult.Failed]),
    skipped = result.skippedTools
  )

private[core] enum LegacyLockValidationError:
  case InvalidPath(error: LockCommandError)
  case ValidationFailed(error: LockedApplyError)

private[core] final class ResolvingBinaryInstallerService(
    httpTextClient: HttpTextClient,
    resolutionOptions: ResolutionOptions,
    installer: DirectBinaryInstaller,
    stateStore: ApplyStateStore,
    metadataClient: BinaryMetadataClient,
    lockFileStore: LockFileStore,
    profileSource: ProfileSource
) extends BinaryInstaller:

  def plan(request: PlanRequest): Either[ResolvePlanError, ResolvedPlan] =
    resolveSelectedPreparedPlan(request.profile, request.selection).map(_.plan)

  def lock(request: LockRequest): Either[LockCommandError, LockReport] =
    resolveSelectedPreparedPlan(request.profile, request.selection)
      .left
      .map(LockCommandError.ResolutionFailed.apply)
      .flatMap(prepared => writeLock(prepared, request.outputPath))

  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    val eventContext = InstallerEventContext.start(eventObserver)
    renderSelectedPlanWithEvents(options, eventContext)

  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    val eventContext = InstallerEventContext.start(eventObserver)
    eventContext.emit(InstallerEvent.ResolvingStarted(options.configPath, _))
    // Both failure channels are already rendered InstallerResults, so the whole thing is one flat
    // sequence and the happy path stays at the top indentation level rather than the bottom of a
    // three-deep pyramid.
    val outcome =
      for
        prepared <- resolveSelectedPreparedPlan(options)
          .left.map(error => failed(renderError(error), eventContext))
        lockedProvenance <- validateLockIfRequested(options, prepared)
          .left.map(error => failed(renderLegacyLockValidationError(error), eventContext))
      yield
        val lockedPrepared = lockedProvenance.fold(prepared)(applyLockedChecksums(prepared, _))
        val statePath      = StatePathResolver.selected(options, lockedPrepared.plan)
        eventContext.emit(InstallerEvent.PlanReady(
          lockedPrepared.plan.tools.map(_.name),
          statePath,
          _
        ))
        val result = StatefulApplyRunner.run(
          options,
          lockedPrepared,
          installer,
          stateStore,
          eventContext
        )
        emitSummary(result, statePath, eventContext)
        result
    outcome.merge

  def versions(options: InstallerOptions): InstallerResult =
    resolveSelectedPreparedPlan(options).fold(renderError, renderVersions)

  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult =
    val result = LegacyPathParser
      .parse(lockOptions.outputPath)
      .left
      .map(invalid => LockCommandError.InvalidPath(invalid.path, invalid.message))
      .flatMap: outputPath =>
        resolveSelectedPreparedPlan(options)
          .left
          .map(LockCommandError.ResolutionFailed.apply)
          .flatMap(prepared => writeLock(prepared, outputPath))
    result.fold(renderLockCommandError, renderLockReport)

  private def renderSelectedPlanWithEvents(
      options: InstallerOptions,
      eventContext: InstallerEventContext
  ): InstallerResult =
    eventContext.emit(InstallerEvent.ResolvingStarted(options.configPath, _))
    val outcome =
      for
        prepared <- resolveSelectedPreparedPlan(options)
          .left.map(error => failed(renderError(error), eventContext))
        statePath <- validatedStatePath(options, prepared.plan)
          .left.map(error => failed(renderStateError(error), eventContext))
        lockedProvenance <- validateLockIfRequested(options, prepared)
          .left.map(error => failed(renderLegacyLockValidationError(error), eventContext))
      yield
        // The plan shows the checksums apply would verify, so it takes the same locked overlay.
        val lockedPrepared = lockedProvenance.fold(prepared)(applyLockedChecksums(prepared, _))
        eventContext.emit(InstallerEvent.PlanReady(
          lockedPrepared.plan.tools.map(_.name),
          statePath,
          _
        ))
        val result = PlanRenderer.render(lockedPrepared.plan, statePath, lockedProvenance)
        emitSummary(result, statePath, eventContext)
        result
    outcome.merge

  private def resolveSelectedPreparedPlan(
      options: InstallerOptions
  ): Either[ResolvePlanError, PreparedPlan] =
    prepare(profileSource.load(options.configPath), options.selection)

  private def resolveSelectedPreparedPlan(
      profileInput: ProfileInput,
      selection: ToolSelection
  ): Either[ResolvePlanError, PreparedPlan] = prepare(profileSource.load(profileInput), selection)

  private def prepare(
      loadedProfile: Either[
        binstaller.config.ConfigLoadError,
        binstaller.config.BinaryDistributionProfile
      ],
      selection: ToolSelection
  ): Either[ResolvePlanError, PreparedPlan] = loadedProfile match
    case Left(error)    => Left(ResolvePlanError.ConfigLoadFailed(error))
    case Right(profile) =>
      PlanResolver.resolve(profile, resolutionOptions, httpTextClient, selection).map: plan =>
        PreparedPlan(
          profile,
          profile.metadata.name,
          ManifestFingerprint.profile(profile),
          plan
        )

  private def writeLock(
      prepared: PreparedPlan,
      outputPath: Path
  ): Either[LockCommandError, LockReport] = buildLock(prepared).flatMap: lockFile =>
    val normalizedPath = outputPath.toAbsolutePath.normalize()
    // Absent or unreadable, the old file has nothing to keep and is replaced, as it always was.
    val existing = lockFileStore.load(normalizedPath).toOption
    saveLock(normalizedPath, LockFileMerge.merge(lockFile, existing, prepared.profile.spec.plan))

  private def buildLock(prepared: PreparedPlan): Either[LockCommandError, LockFile] =
    LockFileBuilder
      .build(prepared, metadataClient)
      .left
      .map(error => LockCommandError.InspectionFailed(error.toolName, error.message))

  private def saveLock(
      normalizedPath: Path,
      merged: MergedLockFile
  ): Either[LockCommandError, LockReport] = lockFileStore
    .save(normalizedPath, merged.lockFile)
    .left
    .map(LockCommandError.SaveFailed.apply)
    .map(_ => LockReport(normalizedPath, merged.lockFile, merged.keptEntries))

  // Plan prints the state file apply would use, so it holds that name to apply's rule. Checked
  // before the lock gate, which downloads every selected artifact.
  private def validatedStatePath(
      options: InstallerOptions,
      plan: ResolvedPlan
  ): Either[ApplyStateError.InvalidPath, Option[String]] =
    StatePathResolver.selected(options, plan).fold(Right(None)): rawPath =>
      StatePathResolver.resolve(rawPath, stateStore.cwd).map(_ => Some(rawPath))

  private def renderStateError(error: ApplyStateError): InstallerResult = InstallerResult(
    Vector(RenderSafety.display(ApplyStateError.render(error), resolutionOptions.redactions)),
    InstallerRunStatus.Failed
  )

  /** Emit the run summary for a failed run and return its rendered result unchanged. */
  private def failed(
      result: InstallerResult,
      eventContext: InstallerEventContext
  ): InstallerResult =
    emitSummary(result, stateFilePath = None, eventContext)
    result

  private def emitSummary(
      result: InstallerResult,
      stateFilePath: Option[String],
      eventContext: InstallerEventContext
  ): Unit =
    val statistics = InstallerRunStatistics.fromResult(result)
    eventContext.emit(InstallerEvent.Summary(
      result.status,
      installed = statistics.installed,
      failed = statistics.failed,
      skipped = statistics.skipped,
      stateFilePath = stateFilePath,
      _
    ))

  private def renderVersions(prepared: PreparedPlan): InstallerResult =
    val statuses = GitHubReleaseVersions.versionStatusByTool(prepared.plan, httpTextClient)
    val rows     = prepared.plan.tools.map: tool =>
      VersionSummaryRow(
        packageName = tool.name.value,
        version = ResolvedVersion.render(tool.version),
        newer = statuses.get(tool.name) match
          case Some(GitHubReleaseVersions.LatestReleaseStatus.Newer(tag)) =>
            NewerVersionStatus.Available(tag)
          case Some(GitHubReleaseVersions.LatestReleaseStatus.UpToDate) =>
            NewerVersionStatus.UpToDate
          case _ => NewerVersionStatus.Unknown
      )
    // Redact once here, before the rows leave core, so a renderer consuming `versionRows` gets the
    // same protection as one reading `lines`.
    val safeRows = rows.map: row =>
      row.copy(
        packageName = RenderSafety.display(row.packageName, prepared.plan.redactions),
        version = RenderSafety.display(row.version, prepared.plan.redactions),
        newer = row.newer match
          case NewerVersionStatus.Available(tag) =>
            NewerVersionStatus.Available(RenderSafety.display(tag, prepared.plan.redactions))
          case other => other
      )
    InstallerResult(
      RenderSafety.displayLines(
        "binstaller versions" +: renderVersionSummaryTable(rows),
        prepared.plan.redactions
      ),
      InstallerRunStatus.Succeeded,
      versionRows = safeRows
    )

  private def renderVersionSummaryTable(rows: Vector[VersionSummaryRow]): Vector[String] =
    val headers =
      VersionSummaryRow("package", "version", NewerVersionStatus.Available("newer version"))
    val displayRows = headers +: rows
    val layout      = VersionSummaryRow.layoutFor(displayRows)
    displayRows.map: row =>
      s"${row.packageName.padTo(layout.packageWidth, ' ')}  " +
        s"${row.version.padTo(layout.versionWidth, ' ')}  " +
        NewerVersionStatus.render(row.newer)

  // The resolved plan's redactions are a copy of resolutionOptions.redactions, so the options
  // field redacts error surfaces even on paths where resolution failed and no plan exists.
  private def renderError(error: ResolvePlanError): InstallerResult = InstallerResult(
    ResolvePlanError.renderLines(error, resolutionOptions.redactions),
    InstallerRunStatus.Failed
  )

  private def renderLockCommandError(error: LockCommandError): InstallerResult = InstallerResult(
    LockCommandError.renderLines(error, resolutionOptions.redactions),
    InstallerRunStatus.Failed
  )

  private def renderLockReport(report: LockReport): InstallerResult = InstallerResult(
    Vector(
      s"wrote lock file: ${report.path}",
      s"profile: ${report.lockFile.profileName}",
      s"manifest fingerprint: ${report.lockFile.manifestFingerprint}",
      s"tools: ${report.lockFile.tools.size}",
      s"kept from existing lock file: ${report.keptEntries}",
      s"checksums: ${LockFileChecksum.summary(report.lockFile.tools)}"
    ),
    InstallerRunStatus.Succeeded
  )

  private def validateLockIfRequested(
      options: InstallerOptions,
      prepared: PreparedPlan
  ): Either[LegacyLockValidationError, Option[LockedApplyProvenance]] = options.lockedApply match
    case LockedApplyMode.Disabled => Right(None)
    case LockedApplyMode.Enabled  => LegacyPathParser
        .parse(options.lockPath)
        .left
        .map: invalid =>
          LegacyLockValidationError.InvalidPath(
            LockCommandError.InvalidPath(invalid.path, invalid.message)
          )
        .flatMap: path =>
          LockedApplyValidator
            .validate(prepared, path, lockFileStore, metadataClient)
            .left
            .map(LegacyLockValidationError.ValidationFailed.apply)
            .map(Some(_))

  private def renderLegacyLockValidationError(
      error: LegacyLockValidationError
  ): InstallerResult = error match
    case LegacyLockValidationError.InvalidPath(lockError) => renderLockCommandError(lockError)
    case LegacyLockValidationError.ValidationFailed(lockedApplyError) =>
      renderLockedApplyError(lockedApplyError)

  private def renderLockedApplyError(error: LockedApplyError): InstallerResult = InstallerResult(
    LockedApplyError.renderLines(error, resolutionOptions.redactions),
    InstallerRunStatus.Failed
  )

  /**
   * Pin each tool that the manifest leaves without a checksum to its locked digest.
   *
   * A tool with its own checksum keeps it. Lock validation already proved the locked digest equal,
   * and the tool's own source says where the value came from.
   */
  private def applyLockedChecksums(
      prepared: PreparedPlan,
      locked: LockedApplyProvenance
  ): PreparedPlan = prepared.copy(plan = prepared.plan.copy(tools = prepared.plan.tools.map: tool =>
    lockedChecksum(tool, locked).fold(tool): checksum =>
      tool.copy(download = tool.download.copy(checksum = Some(checksum)))))

  private def lockedChecksum(
      tool: ResolvedTool,
      locked: LockedApplyProvenance
  ): Option[ResolvedChecksum] =
    if tool.download.checksum.nonEmpty then None
    else
      locked.tools.get(tool.name).flatMap(_.checksum).map: checksum =>
        ResolvedChecksum(
          ChecksumAlgorithm.Sha256,
          // Lock validation already rejected a malformed locked digest, so no parse is repeated
          // here. A value that still got through matches no artifact, and apply refuses the tool.
          Sha256Digest.trusted(checksum.value),
          ResolvedChecksumSource.Locked(locked.path)
        )
