package binstaller.core

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
    resolveSelectedPreparedPlan(request).map(_.plan)

  def lock(request: LockRequest): Either[LockCommandError, LockReport] =
    resolveSelectedPreparedPlan(request)
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
        val statePath      = configuredStatePath(options, lockedPrepared.plan)
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
        lockedProvenance <- validateLockIfRequested(options, prepared)
          .left.map(error => failed(renderLegacyLockValidationError(error), eventContext))
      yield
        // A pure lookup over options and the resolved policy, so computing it after the lock check
        // rather than before changes nothing observable.
        val statePath = configuredStatePath(options, prepared.plan)
        eventContext.emit(InstallerEvent.PlanReady(prepared.plan.tools.map(_.name), statePath, _))
        val result = PlanRenderer.render(prepared.plan, lockedProvenance)
        emitSummary(result, statePath, eventContext)
        result
    outcome.merge

  private def resolveSelectedPreparedPlan(
      options: InstallerOptions
  ): Either[ResolvePlanError, PreparedPlan] = resolveFromOptions(options).flatMap: prepared =>
    ToolSelector.select(prepared.plan, options.selection).map: selected =>
      prepared.copy(plan = selected)

  private def resolveSelectedPreparedPlan(
      request: PlanRequest
  ): Either[ResolvePlanError, PreparedPlan] =
    resolveSelectedPreparedPlan(request.profile, request.selection)

  private def resolveSelectedPreparedPlan(
      request: LockRequest
  ): Either[ResolvePlanError, PreparedPlan] =
    resolveSelectedPreparedPlan(request.profile, request.selection)

  private def resolveSelectedPreparedPlan(
      profileInput: ProfileInput,
      selection: ToolSelection
  ): Either[ResolvePlanError, PreparedPlan] = resolveFromProfileInput(profileInput).flatMap:
    prepared =>
      ToolSelector.select(prepared.plan, selection).map: selected =>
        prepared.copy(plan = selected)

  private def resolveFromOptions(
      options: InstallerOptions
  ): Either[ResolvePlanError, PreparedPlan] = prepare(profileSource.load(options.configPath))

  private def resolveFromProfileInput(
      profileInput: ProfileInput
  ): Either[ResolvePlanError, PreparedPlan] = prepare(profileSource.load(profileInput))

  private def prepare(
      loadedProfile: Either[
        binstaller.config.ConfigLoadError,
        binstaller.config.BinaryDistributionProfile
      ]
  ): Either[ResolvePlanError, PreparedPlan] = loadedProfile match
    case Left(error)    => Left(ResolvePlanError.ConfigLoadFailed(error))
    case Right(profile) => PlanResolver.resolve(profile, resolutionOptions, httpTextClient).map:
        plan =>
          PreparedPlan(
            profile,
            profile.metadata.name,
            ManifestFingerprint.profile(profile),
            plan
          )

  private def writeLock(
      prepared: PreparedPlan,
      outputPath: Path
  ): Either[LockCommandError, LockReport] =
    buildLock(prepared).flatMap(lockFile => saveLock(outputPath, lockFile))

  private def buildLock(prepared: PreparedPlan): Either[LockCommandError, LockFile] =
    LockFileBuilder
      .build(prepared, metadataClient)
      .left
      .map(error => LockCommandError.InspectionFailed(error.toolName, error.message))

  private def saveLock(
      outputPath: Path,
      lockFile: LockFile
  ): Either[LockCommandError, LockReport] =
    val normalizedPath = outputPath.toAbsolutePath.normalize()
    lockFileStore
      .save(normalizedPath, lockFile)
      .left
      .map(LockCommandError.SaveFailed.apply)
      .map(_ => LockReport(normalizedPath, lockFile))

  private def configuredStatePath(
      options: InstallerOptions,
      plan: ResolvedPlan
  ): Option[String] = options.statePath.orElse(plan.policy.stateFile)

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
          case Some(GitHubReleaseVersions.LatestReleaseStatus.Unknown) => NewerVersionStatus.Unknown
          case _ => NewerVersionStatus.UpToDate
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
    val displayRows  = headers +: rows
    val packageWidth = displayRows.map(_.packageName.length).max
    val versionWidth = displayRows.map(_.version.length).max
    displayRows.map: row =>
      s"${row.packageName.padTo(packageWidth, ' ')}  " +
        s"${row.version.padTo(versionWidth, ' ')}  " +
        NewerVersionStatus.render(row.newer)

  private def renderError(error: ResolvePlanError): InstallerResult =
    InstallerResult(ResolvePlanError.renderLines(error), InstallerRunStatus.Failed)

  private def renderLockCommandError(error: LockCommandError): InstallerResult =
    InstallerResult(LockCommandError.renderLines(error), InstallerRunStatus.Failed)

  private def renderLockReport(report: LockReport): InstallerResult = InstallerResult(
    Vector(
      s"wrote lock file: ${report.path}",
      s"profile: ${report.lockFile.profileName}",
      s"manifest fingerprint: ${report.lockFile.manifestFingerprint}",
      s"tools: ${report.lockFile.tools.size}",
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

  private def renderLockedApplyError(error: LockedApplyError): InstallerResult =
    InstallerResult(LockedApplyError.renderLines(error), InstallerRunStatus.Failed)

  private def applyLockedChecksums(
      prepared: PreparedPlan,
      locked: LockedApplyProvenance
  ): PreparedPlan = prepared.copy(plan = prepared.plan.copy(tools = prepared.plan.tools.map: tool =>
    locked.tools.get(tool.name).flatMap(_.checksum) match
      // Locked-apply validation rejects any malformed locked digest before this runs, so the parse
      // cannot fail here; leaving the tool untouched if it somehow did keeps this total without
      // inventing a digest.
      case Some(checksum) => Sha256Digest.fromString(checksum.value).toOption.fold(tool): digest =>
          tool.copy(download =
            tool.download.copy(checksum =
              Some(ResolvedChecksum(
                binstaller.config.ChecksumAlgorithm.Sha256,
                digest,
                ResolvedChecksumSource.Configured
              ))
            )
          )
      case None => tool))
