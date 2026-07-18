package binstaller.cli

import binstaller.core.DownloadProgressStatus
import binstaller.core.InstallerEvent
import binstaller.core.InstallerEventObserver
import binstaller.core.InstallerRunStatus
import binstaller.core.RenderSafety

import java.io.PrintWriter
import java.net.URI

private[cli] final class CliApplyEventRenderer(
    out: PrintWriter,
    outputStyle: CliOutputStyle = CliOutputStyle.Ansi
) extends InstallerEventObserver:
  private val width                                   = 30
  private var lastBuckets: Map[String, Int]           = Map.empty
  private var activeTools: Set[String]                = Set.empty
  private var downloadOrder: Vector[String]           = Vector.empty
  private var downloads: Map[String, DownloadRow]     = Map.empty
  private var concurrentLineMode: Boolean             = false
  private var progressBlockTools: Vector[String]      = Vector.empty
  private var progressBlockHeight: Int                = 0
  private var activeLineLength: Int                   = 0
  private var summary: Option[InstallerEvent.Summary] = None

  def onEvent(event: InstallerEvent): Unit = event match
    case progress: InstallerEvent.DownloadProgress => renderProgress(progress)
    case value: InstallerEvent.Summary             => summary = Some(value)
    case _                                         => ()

  def finish(): Unit =
    if concurrentLineMode then finishProgressBlock()
    else clearActiveLine()

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

    downloads = downloads.updated(
      progress.toolName,
      DownloadRow(
        progress.toolName,
        progress.url,
        progress.downloadedBytes,
        progress.totalBytes,
        progress.status
      )
    )

  private def renderStarted(progress: InstallerEvent.DownloadProgress): Unit =
    lastBuckets = lastBuckets.updated(progress.toolName, -1)
    if outputStyle.supportsAnsi then
      if !concurrentLineMode && activeTools.size > 1 then enableConcurrentLineMode()
      else if concurrentLineMode then
        addToProgressBlock(progress.toolName)
        redrawProgressBlock()
      else renderInPlace(renderActive(progress))

  private def renderAdvanced(progress: InstallerEvent.DownloadProgress): Unit =
    val bucket = progressBucket(progress.downloadedBytes, progress.totalBytes, concurrentLineMode)
    if outputStyle.supportsAnsi && bucket != lastBuckets.getOrElse(progress.toolName, -1) then
      lastBuckets = lastBuckets.updated(progress.toolName, bucket)
      if concurrentLineMode then redrawProgressBlock()
      else renderInPlace(renderActive(progress))

  private def renderFinished(progress: InstallerEvent.DownloadProgress): Unit =
    lastBuckets = lastBuckets.updated(progress.toolName, 100)
    if concurrentLineMode then
      addToProgressBlock(progress.toolName)
      redrawProgressBlock()
      if activeTools.isEmpty then finishProgressBlock()
    else if outputStyle.supportsAnsi then renderCompleted(renderCompletedLine(progress))
    else renderCompletedPlain(renderCompletedLine(progress))

  private def enableConcurrentLineMode(): Unit =
    clearActiveLine()
    concurrentLineMode = true
    progressBlockTools = activeRows.map(_.toolName)
    redrawProgressBlock()

  private def addToProgressBlock(toolName: String): Unit =
    if !progressBlockTools.contains(toolName) then
      progressBlockTools = progressBlockTools :+ toolName

  private def redrawProgressBlock(): Unit =
    val rows = progressBlockRows
    if progressBlockHeight > 0 then out.print(s"\u001b[${progressBlockHeight}A")
    rows.foreach: row =>
      val line = renderRow(row)
      out.print(s"\r\u001b[2K${line.styled}\n")
    out.flush()
    progressBlockHeight = rows.size

  private def progressBlockRows: Vector[DownloadRow] = progressBlockTools.flatMap(downloads.get)

  private def finishProgressBlock(): Unit =
    out.flush()
    concurrentLineMode = false
    progressBlockTools = Vector.empty
    progressBlockHeight = 0
    activeLineLength = 0

  private def activeRows: Vector[DownloadRow] = downloadOrder.flatMap: toolName =>
    downloads.get(toolName).filter(row => activeTools.contains(row.toolName))

  private def clearActiveLine(): Unit = if activeLineLength > 0 then
    out.print("\r\u001b[K")
    out.flush()
    activeLineLength = 0

  private def progressBucket(
      downloadedBytes: Long,
      totalBytes: Option[Long],
      lineMode: Boolean
  ): Int = totalBytes.filter(_ > 0L) match
    case Some(total) =>
      val percent = ((downloadedBytes.toDouble / total.toDouble) * 100.0).floor.toInt
      if lineMode then (percent / 10) * 10 else percent
    case None => (downloadedBytes / (1024L * 1024L)).toInt

  private def renderActive(progress: InstallerEvent.DownloadProgress): ProgressLine = renderActive(
    DownloadRow(
      progress.toolName,
      progress.url,
      progress.downloadedBytes,
      progress.totalBytes,
      progress.status
    )
  )

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
    renderCompletedLine(DownloadRow(
      progress.toolName,
      progress.url,
      progress.downloadedBytes,
      progress.totalBytes,
      progress.status
    ))

  private def renderCompletedLine(row: DownloadRow): ProgressLine =
    val label  = downloadLabel(row.toolName, row.url)
    val bar    = progressBar(row.downloadedBytes, row.totalBytes)
    val bytes  = byteText(row.downloadedBytes, row.totalBytes)
    val plain  = s"✅ completed $label ${bar.plain} $bytes"
    val styled = outputStyle.color(s"✅ completed $label")(fansi.Color.Green) +
      s" ${bar.styled} ${outputStyle.color(bytes)(fansi.Color.Green)}"
    ProgressLine(plain, styled)

  private def renderInPlace(line: ProgressLine): Unit =
    out.print(s"\r${line.styled}\u001b[K")
    out.flush()
    activeLineLength = line.visibleLength

  private def renderCompleted(line: ProgressLine): Unit =
    out.print(s"\r${line.styled}\u001b[K\n")
    out.flush()
    activeLineLength = 0

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

  private def downloadLabel(toolName: String, url: String): String =
    val safeToolName = RenderSafety.terminalLine(toolName)
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

private[cli] final case class DownloadRow(
    toolName: String,
    url: String,
    downloadedBytes: Long,
    totalBytes: Option[Long],
    status: DownloadProgressStatus
)

private[cli] final case class ProgressLine(plain: String, styled: String):
  def visibleLength: Int = plain.length

private[cli] object CliApplyOutput:

  def colorLines(
      lines: Vector[String],
      outputStyle: CliOutputStyle = CliOutputStyle.Ansi
  ): Vector[String] = lines.map(colorLine(_, outputStyle))

  private def colorLine(line: String, outputStyle: CliOutputStyle): String =
    if line.startsWith("installed ") then outputStyle.color(line)(fansi.Color.Green)
    else if line.startsWith("failed ") then outputStyle.color(line)(fansi.Color.Red)
    else line

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
      s"  ${outputStyle.color(s"🚦 exit code: ${event.exitCode}")(fansi.Color.Cyan)}",
      s"  $status"
    )
