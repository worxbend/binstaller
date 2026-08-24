package binstaller.core


import java.nio.file.Path

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
    lockFileStore: LockFileStore,
    profileSource: ProfileSource
) extends BinaryInstallerService:

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
          .left.map(error => failed(renderLockedApplyError(error), eventContext))
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
          installer.fileSystem,
          stateStore,
          eventContext
        )
        emitSummary(result, statePath, eventContext)
        result
    outcome.merge

  def versions(options: InstallerOptions): InstallerResult =
    resolveSelectedPreparedPlan(options).fold(renderError, renderVersions)

  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult =
    resolveSelectedPreparedPlan(options) match
      case Left(error)     => renderError(error)
      case Right(prepared) => LockFileBuilder.build(prepared, metadataClient) match
          case Left(error) => InstallerResult(
              Vector(s"lock inspection failed for tool '${error.toolName}': ${error.message}"),
              InstallerRunStatus.Failed
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
                  InstallerRunStatus.Succeeded
                )
              case Left(error) =>
                InstallerResult(Vector(LockFileError.render(error)), InstallerRunStatus.Failed)

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
          .left.map(error => failed(renderLockedApplyError(error), eventContext))
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

  private def resolveFromOptions(
      options: InstallerOptions
  ): Either[ResolvePlanError, PreparedPlan] = profileSource.load(options.configPath) match
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
        packageName = tool.name,
        version = ResolvedVersion.render(tool.version),
        newer = statuses.get(tool.name) match
          case Some(GitHubReleaseVersions.LatestReleaseStatus.Newer(tag)) =>
            NewerVersionStatus.Available(tag)
          case Some(GitHubReleaseVersions.LatestReleaseStatus.Unknown) =>
            NewerVersionStatus.Unknown
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

  private def validateLockIfRequested(
      options: InstallerOptions,
      prepared: PreparedPlan
  ): Either[LockedApplyError, Option[LockedApplyProvenance]] = options.lockedApply match
    case LockedApplyMode.Disabled => Right(None)
    case LockedApplyMode.Enabled  => LockedApplyValidator
        .validate(prepared, Path.of(options.lockPath), lockFileStore, metadataClient)
        .map(Some(_))

  private def renderLockedApplyError(error: LockedApplyError): InstallerResult =
    InstallerResult(LockedApplyError.renderLines(error), InstallerRunStatus.Failed)

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
