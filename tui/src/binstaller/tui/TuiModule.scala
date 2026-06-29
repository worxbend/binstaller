package binstaller.tui

import binstaller.config.SymlinkPrivilege
import binstaller.core.CoreModule
import binstaller.core.HttpTextClient
import binstaller.core.InstallerOptions
import binstaller.core.InstallerResult
import binstaller.core.ResolvedArchive
import binstaller.core.ResolvedDownload
import binstaller.core.ResolvedExtractMapping
import binstaller.core.ResolvedPlanSnapshot
import binstaller.core.ResolvedSymlink
import binstaller.core.ResolvedTool
import binstaller.core.ResolvedVersion
import binstaller.core.ResolvePlanError

object TuiModule:
  def modulePath: Vector[String] = CoreModule.modulePath :+ "tui"

  def start(request: TuiRequest): InstallerResult =
    start(request, HttpTextClient.jdk, PlanningTuiSettings.default)

  def start(
      request: TuiRequest,
      httpTextClient: HttpTextClient,
      settings: PlanningTuiSettings
  ): InstallerResult = ResolvedPlanSnapshot.resolve(request.options, httpTextClient) match
    case Left(error) => InstallerResult(
        s"binstaller ${request.mode.commandName} --tui" +: ResolvePlanError.renderLines(error),
        1
      )
    case Right(snapshot) =>
      val model = PlanningTuiModel.fromSnapshot(snapshot, request, settings)
      InstallerResult(PlanningTuiRenderer.render(model), 0)

enum TuiMode:
  case Plan, Apply

  def commandName: String = this match
    case Plan  => "plan"
    case Apply => "apply"

final case class TuiRequest(mode: TuiMode, options: InstallerOptions)

final case class TuiViewport(width: Int, height: Int)

object TuiViewport:
  val default: TuiViewport = TuiViewport(120, 36)

final case class PlanningTuiSettings(
    viewport: TuiViewport,
    appVersion: String,
    hostSummary: String,
    selectedIndex: Int,
    filter: Option[String],
    logs: Vector[String]
)

object PlanningTuiSettings:

  def default: PlanningTuiSettings = PlanningTuiSettings(
    viewport = TuiViewport.default,
    appVersion = "dev",
    hostSummary = TuiHost.currentSummary,
    selectedIndex = 0,
    filter = None,
    logs = Vector.empty
  )

final case class PlanningTuiHeader(
    appName: String,
    appVersion: String,
    mode: String,
    manifestName: String,
    manifestKind: String,
    configPath: String,
    stateFilePath: Option[String],
    hostSummary: String,
    selectionText: String,
    filterText: String
)

final case class PlanningTuiRow(
    index: Int,
    selected: Boolean,
    status: PlanningTuiStatus,
    name: String,
    kind: String,
    version: String,
    installDir: String,
    checksumState: String,
    riskMarkers: Vector[String]
)

final case class PlanningTuiDetail(
    name: String,
    lines: Vector[String]
)

final case class PlanningTuiModel(
    viewport: TuiViewport,
    header: PlanningTuiHeader,
    rows: Vector[PlanningTuiRow],
    detail: Option[PlanningTuiDetail],
    logs: Vector[String],
    footer: String,
    keybar: String
)

object PlanningTuiModel:

  def fromSnapshot(
      snapshot: ResolvedPlanSnapshot,
      request: TuiRequest,
      settings: PlanningTuiSettings
  ): PlanningTuiModel =
    val visibleTools  = filteredTools(snapshot.plan.tools, settings.filter)
    val selectedIndex = clampedIndex(settings.selectedIndex, visibleTools)
    val selectedTool  = visibleTools.lift(selectedIndex)
    val rows          = visibleTools.zipWithIndex.map:
      case (tool, index) => rowForTool(index, selected = index == selectedIndex, tool)
    val header = PlanningTuiHeader(
      appName = "binstaller",
      appVersion = settings.appVersion,
      mode = request.mode.commandName,
      manifestName = snapshot.profileName,
      manifestKind = snapshot.manifestKind,
      configPath = snapshot.configPath,
      stateFilePath = snapshot.stateFilePath,
      hostSummary = settings.hostSummary,
      selectionText = selectionText(selectedIndex, visibleTools),
      filterText = settings.filter.filter(_.nonEmpty).getOrElse("none")
    )
    val logs = settings.logs ++ defaultLogs(snapshot, visibleTools.size)
    PlanningTuiModel(
      viewport = settings.viewport,
      header = header,
      rows = rows,
      detail = selectedTool.map(detailForTool),
      logs = logs,
      footer = footerText(snapshot, visibleTools),
      keybar = "q quit | up/down select | / filter | ? help | enter details"
    )

  private def filteredTools(
      tools: Vector[ResolvedTool],
      filter: Option[String]
  ): Vector[ResolvedTool] = filter.map(_.trim).filter(_.nonEmpty) match
    case None        => tools
    case Some(value) =>
      val needle = value.toLowerCase
      tools.filter: tool =>
        tool.name.toLowerCase.contains(needle) ||
          tool.description.exists(_.toLowerCase.contains(needle))

  private def clampedIndex(index: Int, tools: Vector[ResolvedTool]): Int =
    if tools.isEmpty then 0 else index.max(0).min(tools.size - 1)

  private def selectionText(index: Int, tools: Vector[ResolvedTool]): String =
    tools.lift(index) match
      case Some(tool) => s"${index + 1}/${tools.size} ${tool.name}"
      case None       => "none"

  private def rowForTool(index: Int, selected: Boolean, tool: ResolvedTool): PlanningTuiRow =
    val risks  = riskMarkers(tool)
    val status =
      if selected then PlanningTuiStatus.Active
      else if risks.nonEmpty then PlanningTuiStatus.Warning
      else PlanningTuiStatus.Inactive
    PlanningTuiRow(
      index = index + 1,
      selected = selected,
      status = status,
      name = tool.name,
      kind = "binary-tool",
      version = ResolvedVersion.render(tool.version),
      installDir = tool.installDir,
      checksumState = checksumState(tool.download),
      riskMarkers = risks
    )

  private def detailForTool(tool: ResolvedTool): PlanningTuiDetail = PlanningTuiDetail(
    name = tool.name,
    lines = Vector(
      s"name: ${tool.name}",
      s"description: ${tool.description.getOrElse("not provided")}",
      s"version: ${ResolvedVersion.render(tool.version)}",
      s"install dir: ${tool.installDir}",
      s"download url: ${tool.download.url}",
      s"download file: ${joinPath(tool.installDir, tool.download.filename)}",
      s"checksum: ${checksumDetail(tool.download)}"
    ) ++ archiveLines(tool.download.archive) ++ symlinkLines(tool) ++ dryRunPreview(tool)
  )

  private def archiveLines(archive: Option[ResolvedArchive]): Vector[String] = archive match
    case None        => Vector("archive: none")
    case Some(value) => Vector(s"archive: ${value.original.archiveType.value}") ++
        mappingLines("archive file", value.files) ++
        mappingLines("archive directory", value.directories)

  private def mappingLines(
      label: String,
      mappings: Vector[ResolvedExtractMapping]
  ): Vector[String] =
    if mappings.isEmpty then Vector.empty
    else mappings.map(mapping => s"$label: ${mapping.from} -> ${mapping.to}")

  private def symlinkLines(tool: ResolvedTool): Vector[String] =
    if tool.symlinks.isEmpty then Vector("symlinks: none")
    else Vector("symlinks:") ++ tool.symlinks.map(renderSymlink(tool, _))

  private def renderSymlink(tool: ResolvedTool, symlink: ResolvedSymlink): String =
    val privilege = symlink.privilege match
      case SymlinkPrivilege.User => "local"
      case SymlinkPrivilege.Sudo => "sudo"
    s"  $privilege: ${absoluteOrInstallPath(tool.installDir, symlink.target)} -> " +
      absoluteOrInstallPath(tool.installDir, symlink.path)

  private def dryRunPreview(tool: ResolvedTool): Vector[String] =
    val strategy = tool.download.archive match
      case Some(archive) => s"extract ${archive.original.archiveType.value} archive mappings"
      case None          => tool.executables.headOption match
          case Some(executable) =>
            s"place direct binary at ${joinPath(tool.installDir, executable.path)}"
          case None => "no executable target configured"
    Vector(
      "dry-run operation preview:",
      s"  1. create install directory ${tool.installDir}",
      s"  2. download ${tool.download.url} to ${joinPath(tool.installDir, tool.download.filename)}",
      s"  3. ${checksumOperation(tool.download)}",
      s"  4. $strategy",
      "  5. apply executable modes and replace install directory"
    ) ++ tool.symlinks.zipWithIndex.map:
      case (symlink, index) => s"  ${index + 6}. ${symlinkCommand(tool, symlink)}"

  private def symlinkCommand(tool: ResolvedTool, symlink: ResolvedSymlink): String =
    val destination = absoluteOrInstallPath(tool.installDir, symlink.path)
    val target      = absoluteOrInstallPath(tool.installDir, symlink.target)
    symlink.privilege match
      case SymlinkPrivilege.User => s"ln -sfn '$target' '$destination'"
      case SymlinkPrivilege.Sudo => s"sudo ln -sfn '$target' '$destination'"

  private def defaultLogs(snapshot: ResolvedPlanSnapshot, visibleTools: Int): Vector[String] =
    Vector(
      s"resolved manifest ${snapshot.profileName}",
      s"loaded $visibleTools selected plan entr${if visibleTools == 1 then "y" else "ies"}",
      "downloads are not started in the planning TUI"
    )

  private def footerText(snapshot: ResolvedPlanSnapshot, tools: Vector[ResolvedTool]): String =
    val sudoSymlinks     = tools.flatMap(_.symlinks).count(_.privilege == SymlinkPrivilege.Sudo)
    val missingChecksums = tools.count(_.download.checksum.isEmpty)
    val state            = snapshot.stateFilePath.getOrElse("not configured")
    s"tools ${tools.size} | sudo symlinks $sudoSymlinks | missing checksums $missingChecksums | state $state"

  private def riskMarkers(tool: ResolvedTool): Vector[String] =
    val checksumRisk = tool.download.checksum match
      case None    => Vector("no-checksum")
      case Some(_) => Vector.empty
    checksumRisk ++ dynamicVersionRisk(tool.version) ++ sudoRisk(tool)

  private def dynamicVersionRisk(version: ResolvedVersion): Vector[String] = version match
    case ResolvedVersion.Concrete(_)         => Vector.empty
    case ResolvedVersion.DynamicLatestUrl(_) => Vector("dynamic-version")

  private def sudoRisk(tool: ResolvedTool): Vector[String] =
    if tool.symlinks.exists(_.privilege == SymlinkPrivilege.Sudo) then Vector("sudo")
    else Vector.empty

  private def checksumState(download: ResolvedDownload): String = download.checksum match
    case Some(value) => value.algorithm.value
    case None        => "missing"

  private def checksumDetail(download: ResolvedDownload): String = download.checksum match
    case Some(value) => s"${value.algorithm.value} ${value.value}"
    case None        => "not configured"

  private def checksumOperation(download: ResolvedDownload): String = download.checksum match
    case Some(value) => s"verify ${value.algorithm.value} checksum ${value.value}"
    case None        => "skip checksum verification because none is configured"

  private def absoluteOrInstallPath(installDir: String, path: String): String =
    if path.startsWith("/") then path else joinPath(installDir, path)

  private def joinPath(parent: String, child: String): String =
    if parent.endsWith("/") then s"$parent$child" else s"$parent/$child"

enum PlanningTuiStatus(val label: String):
  case Completed extends PlanningTuiStatus("completed")
  case Failed    extends PlanningTuiStatus("failed")
  case Warning   extends PlanningTuiStatus("warning")
  case Active    extends PlanningTuiStatus("active")
  case Skipped   extends PlanningTuiStatus("skipped")
  case Inactive  extends PlanningTuiStatus("inactive")

object PlanningTuiStatus:

  val legendOrder: Vector[PlanningTuiStatus] = Vector(
    Completed,
    Failed,
    Warning,
    Active,
    Skipped,
    Inactive
  )

  def style(status: PlanningTuiStatus, value: String): String = status match
    case Completed => fansi.Color.Green(value).toString
    case Failed    => fansi.Color.Red(value).toString
    case Warning   => fansi.Color.Yellow(value).toString
    case Active    => fansi.Color.Cyan(value).toString
    case Skipped   => fansi.Color.LightGray(value).toString
    case Inactive  => value

object PlanningTuiRenderer:

  def render(model: PlanningTuiModel): Vector[String] =
    val width = model.viewport.width.max(80)
    header(model, width) ++
      table(model.rows, width) ++
      detail(model.detail, width) ++
      logs(model.logs, width) ++
      footer(model, width)

  private def header(model: PlanningTuiModel, width: Int): Vector[String] =
    val header = model.header
    Vector(
      PlanningTuiStatus.style(
        PlanningTuiStatus.Active,
        fit(
          s"${header.appName} ${header.appVersion} | mode ${header.mode} | " +
            s"manifest ${header.manifestName} (${header.manifestKind})",
          width
        )
      ),
      s"config ${header.configPath}",
      s"state ${header.stateFilePath.getOrElse("not configured")}",
      fit(
        s"host ${header.hostSummary} | selection ${header.selectionText} | filter ${header.filterText}",
        width
      ),
      separator(width)
    )

  private def table(rows: Vector[PlanningTuiRow], width: Int): Vector[String] =
    val header = s"${cell("#", 4)} ${cell("status", 10)} ${cell("name", 20)} ${cell("kind", 12)} " +
      s"${cell("version", 18)} ${cell("install dir", 26)} ${cell("checksum", 10)} risk"
    val body =
      if rows.isEmpty then Vector(fit("no plan entries match the active filter", width))
      else rows.map(rowLine(_, width))
    Vector(fit("Plan", width), fit(header, width)) ++ body ++ Vector(separator(width))

  private def rowLine(row: PlanningTuiRow, width: Int): String =
    val marker = if row.selected then ">" else " "
    val risks  = if row.riskMarkers.isEmpty then "none" else row.riskMarkers.mkString(",")
    val plain  = s"$marker ${cell(row.index.toString, 3)} ${cell(row.status.label, 10)} " +
      s"${cell(row.name, 20)} ${cell(row.kind, 12)} ${cell(row.version, 18)} " +
      s"${cell(row.installDir, 26)} ${cell(row.checksumState, 10)} ${truncate(risks, 20)}"
    PlanningTuiStatus.style(row.status, fit(plain, width))

  private def detail(detail: Option[PlanningTuiDetail], width: Int): Vector[String] =
    val title = detail.map(value => s"Details: ${value.name}").getOrElse("Details")
    Vector(fit(title, width)) ++ detail.map(_.lines).getOrElse(Vector("no selected entry")) ++
      Vector(separator(width))

  private def logs(lines: Vector[String], width: Int): Vector[String] =
    Vector(fit("Logs", width)) ++ lines.takeRight(6).map(line => fit(line, width)) ++
      Vector(separator(width))

  private def footer(model: PlanningTuiModel, width: Int): Vector[String] =
    val legend = PlanningTuiStatus.legendOrder
      .map(status => PlanningTuiStatus.style(status, status.label))
      .mkString(" ")
    Vector(
      fit(model.footer, width),
      s"status $legend",
      PlanningTuiStatus.style(PlanningTuiStatus.Active, fit(model.keybar, width))
    )

  private def separator(width: Int): String = "-" * width

  private def cell(value: String, width: Int): String =
    val clipped = truncate(value, width)
    clipped + (" " * (width - clipped.length).max(0))

  private def fit(value: String, width: Int): String = cell(value, width)

  private def truncate(value: String, width: Int): String =
    if width <= 0 then ""
    else if value.length <= width then value
    else if width == 1 then "…"
    else s"${value.take(width - 1)}…"

private object TuiHost:

  def currentSummary: String =
    val os   = sys.props.getOrElse("os.name", "unknown-os")
    val arch = sys.props.getOrElse("os.arch", "unknown-arch")
    s"$os/$arch"
