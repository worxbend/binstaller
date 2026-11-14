package binstaller.core

import binstaller.config.PlanEntry

/**
 * Decides which plan entries take part in a run: `when` selectors first, then `--only`/`--skip`.
 */
private[core] object ToolSelector:

  /** Plan entries whose `when` selector matches the host, each with its manifest index. */
  def hostEntries(plan: Vector[PlanEntry], host: HostPlatform): Vector[(PlanEntry, Int)] =
    plan.zipWithIndex.filter((entry, _) => matchesHost(entry, host))

  /**
   * Narrow the host entries to the ones `--only` and `--skip` keep.
   *
   * This works on the manifest, before anything is resolved. A deselected tool then costs no
   * request, and a failure in its version source or its fields cannot fail the run. Its installDir
   * is still compared with the selected ones; see `PlanResolver.resolve`.
   */
  def select(
      plan: Vector[PlanEntry],
      host: HostPlatform,
      selection: ToolSelection
  ): Either[ResolvePlanError.SelectionFailed, Vector[(PlanEntry, Int)]] =
    val problems = unknownNames(plan, selection) ++ hostExcludedOnlyNames(plan, host, selection)

    if problems.nonEmpty then Left(ResolvePlanError.SelectionFailed(problems))
    else Right(selectedEntries(hostEntries(plan, host), selection))

  // Selection input comes from the command line as raw text, so it stays untyped here and is
  // compared against the tool's name value. That keeps the "unknown tool '<raw>'" message
  // echoing exactly what the user typed.
  private def unknownNames(plan: Vector[PlanEntry], selection: ToolSelection): Vector[String] =
    val planNames = plan.map(_.name.value).toSet
    (selection.only ++ selection.skip)
      .distinct
      .filterNot(planNames)
      .map(name => s"unknown tool '$name'")

  // `--skip` of a tool this host already leaves out is satisfied, so one script can run on every
  // platform. `--only` is not: it asks for a tool this host cannot install, and an empty run that
  // reports success would hide that.
  private def hostExcludedOnlyNames(
      plan: Vector[PlanEntry],
      host: HostPlatform,
      selection: ToolSelection
  ): Vector[String] =
    val excludedNames = plan.filterNot(matchesHost(_, host)).map(_.name.value).toSet
    selection.only.distinct.filter(excludedNames).map: name =>
      s"tool '$name' is excluded for this host (${host.osFamily}/${host.architecture}) " +
        "by its when selector"

  private def selectedEntries(
      entries: Vector[(PlanEntry, Int)],
      selection: ToolSelection
  ): Vector[(PlanEntry, Int)] =
    val onlyNames = selection.only.toSet
    val skipNames = selection.skip.toSet
    val included  =
      if onlyNames.isEmpty then entries
      else entries.filter((entry, _) => onlyNames(entry.name.value))

    included.filterNot((entry, _) => skipNames(entry.name.value))

  private def matchesHost(entry: PlanEntry, host: HostPlatform): Boolean =
    entry.when.forall: clause =>
      val osMatches = clause.os.flatMap(_.family).forall: expected =>
        HostPlatform.normalizeOs(expected) == host.osFamily
      val architectureMatches = clause.architecture.forall: expected =>
        HostPlatform.normalizeArchitecture(expected) == host.architecture
      osMatches && architectureMatches
