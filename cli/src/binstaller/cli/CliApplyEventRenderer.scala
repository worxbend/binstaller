package binstaller.cli

import binstaller.config.ToolName

import binstaller.core.DownloadProgressStatus
import binstaller.core.InstallerEvent
import binstaller.core.InstallerEventObserver
import binstaller.core.InstallerRunStatus
import binstaller.core.RenderedTerminalLine
import binstaller.core.RenderSafety
import binstaller.core.ToolResultStatus

import java.io.PrintWriter
import java.net.URI

private[cli] final class CliApplyEventRenderer(
    out: PrintWriter,
    outputStyle: CliOutputStyle = CliOutputStyle.Ansi
) extends InstallerEventObserver:
  private val width                                   = 30
  private val display                                 = ProgressBlockDisplay(out)
  private var lastBuckets: Map[ToolName, Int]         = Map.empty
  private var activeTools: Set[ToolName]              = Set.empty
  private var downloadOrder: Vector[ToolName]         = Vector.empty
  private var downloads: Map[ToolName, DownloadRow]   = Map.empty
  private var summary: Option[InstallerEvent.Summary] = None

  def onEvent(event: InstallerEvent): Unit = event match
    case progress: InstallerEvent.DownloadProgress => renderProgress(progress)
    case value: InstallerEvent.Summary             => summary = Some(value)
    // The state path already reaches the terminal through core's rendered result lines, so there
    // is nothing for a progress row to add here.
    case _: InstallerEvent.StateLoading => ()
    case _                              => ()

  def finish(): Unit =
    if display.enabled then display.finish()
    else display.clearActiveLine()

  def summaryLines: Vector[String] = summary match
    case Some(value) => CliApplyOutput.summary(value, outputStyle)
    case None        => Vector.empty

  private def renderProgress(progress: InstallerEvent.DownloadProgress): Unit =
    updateDownloadRow(progress)
    progress.status match
      case DownloadProgressStatus.Started  => renderStarted(progress)
      case DownloadProgressStatus.Advanced => renderAdvanced(progress)
      case DownloadProgressStatus.Finished => renderFinished(progress)

  private def updateDownloadRow(progress: InstallerEvent.DownloadProgress): Unit =
    if !downloadOrder.contains(progress.toolName) then
      downloadOrder = downloadOrder :+ progress.toolName
    progress.status match
      case DownloadProgressStatus.Started  => activeTools = activeTools + progress.toolName
      case DownloadProgressStatus.Finished => activeTools = activeTools - progress.toolName
      case DownloadProgressStatus.Advanced => ()

    downloads = downloads.updated(progress.toolName, rowOf(progress))

  private def renderStarted(progress: InstallerEvent.DownloadProgress): Unit =
    lastBuckets = lastBuckets.updated(progress.toolName, -1)
    if outputStyle.supportsAnsi then
      if !display.enabled && activeTools.size > 1 then enableConcurrentLineMode()
      else if display.enabled then
        display.add(progress.toolName)
        redrawProgressBlock()
      else display.renderInPlace(renderActive(progress))

  private def renderAdvanced(progress: InstallerEvent.DownloadProgress): Unit =
    val bucket = progressBucket(progress.downloadedBytes, progress.totalBytes, display.enabled)
    if outputStyle.supportsAnsi && bucket != lastBuckets.getOrElse(progress.toolName, -1) then
      lastBuckets = lastBuckets.updated(progress.toolName, bucket)
      if display.enabled then redrawProgressBlock()
      else display.renderInPlace(renderActive(progress))

  private def renderFinished(progress: InstallerEvent.DownloadProgress): Unit =
    lastBuckets = lastBuckets.updated(progress.toolName, 100)
    if display.enabled then
      display.add(progress.toolName)
      redrawProgressBlock()
      if activeTools.isEmpty then display.finish()
    else if outputStyle.supportsAnsi then display.renderCompleted(renderCompletedLine(progress))
    else renderCompletedPlain(renderCompletedLine(progress))

  private def enableConcurrentLineMode(): Unit =
    display.enable(activeRows.map(_.toolName))
    redrawProgressBlock()

  private def redrawProgressBlock(): Unit =
    display.redraw(toolName => downloads.get(toolName).map(renderRow))

  private def activeRows: Vector[DownloadRow] = downloadOrder.flatMap: toolName =>
    downloads.get(toolName).filter(row => activeTools.contains(row.toolName))

  private def progressBucket(
      downloadedBytes: Long,
      totalBytes: Option[Long],
      lineMode: Boolean
  ): Int = totalBytes.filter(_ > 0L) match
    case Some(total) =>
      val percent = ((downloadedBytes.toDouble / total.toDouble) * 100.0).floor.toInt
      if lineMode then (percent / 10) * 10 else percent
    case None => (downloadedBytes / (1024L * 1024L)).toInt

  private def rowOf(progress: InstallerEvent.DownloadProgress): DownloadRow = DownloadRow(
    progress.toolName,
    progress.url,
    progress.downloadedBytes,
    progress.totalBytes,
    progress.status
  )

  private def renderActive(progress: InstallerEvent.DownloadProgress): ProgressLine =
    renderActive(rowOf(progress))

  private def renderRow(row: DownloadRow): ProgressLine = row.status match
    case DownloadProgressStatus.Finished => renderCompletedLine(row)
    case DownloadProgressStatus.Started | DownloadProgressStatus.Advanced => renderActive(row)

  private def renderActive(row: DownloadRow): ProgressLine =
    val label = downloadLabel(row.toolName, row.url)
    val bar   = progressBar(row.downloadedBytes, row.totalBytes)
    val bytes = byteText(row.downloadedBytes, row.totalBytes)
    // Progress text is rendered in-place only for the CLI surface; URL-derived labels are scrubbed
    // before they reach the terminal row.
    val plain  = s"⬇ downloading $label ${bar.plain} $bytes"
    val styled = s"${outputStyle.color("⬇ downloading")(fansi.Color.Cyan)} " +
      s"${outputStyle.color(label)(fansi.Color.Yellow)} ${bar.styled} " +
      outputStyle.color(bytes)(fansi.Color.Cyan)
    ProgressLine(plain, styled)

  private def renderCompletedLine(progress: InstallerEvent.DownloadProgress): ProgressLine =
    renderCompletedLine(rowOf(progress))

  private def renderCompletedLine(row: DownloadRow): ProgressLine =
    val label  = downloadLabel(row.toolName, row.url)
    val bar    = progressBar(row.downloadedBytes, row.totalBytes)
    val bytes  = byteText(row.downloadedBytes, row.totalBytes)
    val plain  = s"✅ completed $label ${bar.plain} $bytes"
    val styled = outputStyle.color(s"✅ completed $label")(fansi.Color.Green) +
      s" ${bar.styled} ${outputStyle.color(bytes)(fansi.Color.Green)}"
    ProgressLine(plain, styled)

  private def renderCompletedPlain(line: ProgressLine): Unit =
    out.println(line.plain)
    out.flush()

  private def progressBar(downloadedBytes: Long, totalBytes: Option[Long]): ProgressLine =
    totalBytes.filter(_ > 0L) match
      case Some(total) =>
        val ratio  = (downloadedBytes.toDouble / total.toDouble).max(0.0).min(1.0)
        val filled = (ratio * width).round.toInt
        val pct    = (ratio * 100.0).round.toInt
        val empty  = width - filled
        val plain  = s"[${"█" * filled}${"░" * empty}] $pct%"
        val styled = s"[${outputStyle.color("█" * filled)(barColor(ratio))}" +
          s"${outputStyle.color("░" * empty)(fansi.Color.Blue)}] " +
          outputStyle.color(s"$pct%")(percentColor(pct))
        ProgressLine(plain, styled)
      case None =>
        val plain = s"[${"█" * width}]"
        ProgressLine(plain, outputStyle.color(plain)(fansi.Color.Magenta))

  private def barColor(ratio: Double): fansi.Attrs =
    if ratio >= 1.0 then fansi.Color.Green
    else if ratio >= 0.7 then fansi.Color.Yellow
    else fansi.Color.Cyan

  private def percentColor(percent: Int): fansi.Attrs =
    if percent >= 100 then fansi.Color.Green
    else if percent >= 70 then fansi.Color.Yellow
    else fansi.Color.Cyan

  private def byteText(downloadedBytes: Long, totalBytes: Option[Long]): String = totalBytes match
    case Some(total) => s"${formatBytes(downloadedBytes)}/${formatBytes(total)}"
    case None        => formatBytes(downloadedBytes)

  private def formatBytes(bytes: Long): String =
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    if bytes >= gib then f"${bytes / gib}%.1f GiB"
    else if bytes >= mib then f"${bytes / mib}%.1f MiB"
    else if bytes >= kib then f"${bytes / kib}%.1f KiB"
    else s"$bytes B"

  private def downloadLabel(toolName: ToolName, url: String): String =
    val safeToolName = RenderSafety.terminalLine(toolName.value)
    val safeFileName = fileName(url)
    if safeFileName == safeToolName then safeToolName else s"$safeToolName $safeFileName"

  private def fileName(url: String): String =
    val fallback = "download"
    scala.util.Try(URI.create(url).getPath)
      .toOption
      .flatMap(path => Option(path).map(_.split('/').toVector.filter(_.nonEmpty).lastOption))
      .flatten
      .map(RenderSafety.terminalLine(_))
      .getOrElse(fallback)

/**
 * Terminal cursor state for apply progress: a single in-place line while one download runs, or a
 * multi-line block redrawn under the cursor once downloads overlap.
 *
 * The renderer keeps the event journal (download rows, active tools); this owns only how rows reach
 * the terminal, so the enable/redraw/finish transitions live behind explicit operations instead of
 * being scattered across the renderer's event handlers.
 */
private[cli] final class ProgressBlockDisplay(out: PrintWriter):
  private var concurrentLineMode: Boolean          = false
  private var progressBlockTools: Vector[ToolName] = Vector.empty
  private var progressBlockHeight: Int             = 0
  private var activeLinePresent: Boolean           = false

  def enabled: Boolean = concurrentLineMode

  def enable(tools: Vector[ToolName]): Unit =
    clearActiveLine()
    concurrentLineMode = true
    progressBlockTools = tools

  def add(toolName: ToolName): Unit = if !progressBlockTools.contains(toolName) then
    progressBlockTools = progressBlockTools :+ toolName

  def redraw(renderRow: ToolName => Option[ProgressLine]): Unit =
    val lines = progressBlockTools.flatMap(renderRow)
    if progressBlockHeight > 0 then out.print(s"\u001b[${progressBlockHeight}A")
    lines.foreach: line =>
      out.print(s"\r\u001b[2K${line.styled}\n")
    out.flush()
    progressBlockHeight = lines.size

  def finish(): Unit =
    out.flush()
    concurrentLineMode = false
    progressBlockTools = Vector.empty
    progressBlockHeight = 0
    activeLinePresent = false

  def renderInPlace(line: ProgressLine): Unit =
    out.print(s"\r${line.styled}\u001b[K")
    out.flush()
    activeLinePresent = true

  def renderCompleted(line: ProgressLine): Unit =
    out.print(s"\r${line.styled}\u001b[K\n")
    out.flush()
    activeLinePresent = false

  def clearActiveLine(): Unit = if activeLinePresent then
    out.print("\r\u001b[K")
    out.flush()
    activeLinePresent = false

private[cli] final case class DownloadRow(
    toolName: ToolName,
    url: String,
    downloadedBytes: Long,
    totalBytes: Option[Long],
    status: DownloadProgressStatus
)

private[cli] final case class ProgressLine(plain: String, styled: String)

private[cli] object CliApplyOutput:

  /**
   * Colour the apply lines that report a tool's outcome, leaving every other line alone.
   *
   * Which lines those are is decided by looking them up among the rendered terminal lines core
   * hands back, each already paired with its status -- not by testing the text for a prefix. A
   * prefix test silently loses its colour the moment core rewords a message, and would colour any
   * future line that happens to begin the same way.
   */
  def colorLines(
      lines: Vector[String],
      renderedTerminalLines: Vector[RenderedTerminalLine],
      outputStyle: CliOutputStyle = CliOutputStyle.Ansi
  ): Vector[String] =
    // Keyed by rendered text, so byte-identical lines collapse into one entry (the last wins).
    val statusByText = renderedTerminalLines.map(rendered => rendered.text -> rendered.status).toMap
    lines.map: line =>
      statusByText.get(line) match
        case Some(ToolResultStatus.Completed) => outputStyle.color(line)(fansi.Color.Green)
        case Some(ToolResultStatus.Failed)    => outputStyle.color(line)(fansi.Color.Red)
        case None                             => line

  def summary(
      event: InstallerEvent.Summary,
      outputStyle: CliOutputStyle = CliOutputStyle.Ansi
  ): Vector[String] =
    val status =
      if event.status == InstallerRunStatus.Succeeded then
        outputStyle.color("🎉 apply completed successfully")(fansi.Color.Green)
      else outputStyle.color("💥 apply finished with errors")(fansi.Color.Red)
    Vector(
      "",
      outputStyle.color("✨ Summary")(fansi.Color.Magenta),
      s"  ${outputStyle.color(s"✅ installed: ${event.installed}")(fansi.Color.Green)}",
      s"  ${outputStyle.color(s"❌ failed: ${event.failed}")(fansi.Color.Red)}",
      s"  ${outputStyle.color(s"⏭ skipped: ${event.skipped}")(fansi.Color.Yellow)}",
      s"  ${outputStyle.color(s"🚦 exit code: ${CliExitCode.of(event.status)}")(fansi.Color.Cyan)}",
      s"  $status"
    )
