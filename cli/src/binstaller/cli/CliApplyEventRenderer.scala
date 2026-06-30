package binstaller.cli

import binstaller.core.DownloadProgressStatus
import binstaller.core.InstallerEvent
import binstaller.core.InstallerEventObserver
import binstaller.core.InstallerRunStatus
import binstaller.core.RenderSafety

import java.io.PrintWriter
import java.net.URI

private[cli] final class CliApplyEventRenderer(
    out: PrintWriter
) extends InstallerEventObserver:
  private val width                                   = 30
  private var lastBuckets: Map[String, Int]           = Map.empty
  private var activeLineLength: Int                   = 0
  private var summary: Option[InstallerEvent.Summary] = None

  def onEvent(event: InstallerEvent): Unit = event match
    case progress: InstallerEvent.DownloadProgress => renderProgress(progress)
    case value: InstallerEvent.Summary             => summary = Some(value)
    case _                                         => ()

  def finish(): Unit = if activeLineLength > 0 then
    out.print(s"\r${" " * activeLineLength}\r")
    out.flush()
    activeLineLength = 0

  def summaryLines: Vector[String] = summary match
    case Some(value) => CliApplyOutput.summary(value)
    case None        => Vector.empty

  private def renderProgress(progress: InstallerEvent.DownloadProgress): Unit =
    progress.status match
      case DownloadProgressStatus.Started =>
        lastBuckets = lastBuckets.updated(progress.url, -1)
        renderInPlace(renderActive(progress.url, downloadedBytes = 0L, progress.totalBytes))
      case DownloadProgressStatus.Advanced =>
        val bucket = progressBucket(progress.downloadedBytes, progress.totalBytes)
        if bucket != lastBuckets.getOrElse(progress.url, -1) then
          lastBuckets = lastBuckets.updated(progress.url, bucket)
          renderInPlace(renderActive(progress.url, progress.downloadedBytes, progress.totalBytes))
      case DownloadProgressStatus.Finished =>
        lastBuckets = lastBuckets.updated(progress.url, 100)
        renderCompleted(
          renderCompletedLine(progress.url, progress.downloadedBytes, progress.totalBytes)
        )

  private def progressBucket(downloadedBytes: Long, totalBytes: Option[Long]): Int =
    totalBytes.filter(_ > 0L) match
      case Some(total) => ((downloadedBytes.toDouble / total.toDouble) * 100.0).floor.toInt
      case None        => (downloadedBytes / (1024L * 1024L)).toInt

  private def renderActive(
      url: String,
      downloadedBytes: Long,
      totalBytes: Option[Long]
  ): ProgressLine =
    val label = fileName(url)
    val bar   = progressBar(downloadedBytes, totalBytes)
    val bytes = byteText(downloadedBytes, totalBytes)
    // Progress text is rendered in-place only for the CLI surface; URL-derived labels are scrubbed
    // before they reach the terminal row.
    val plain  = s"⬇ downloading $label ${bar.plain} $bytes"
    val styled = s"${fansi.Color.Cyan("⬇ downloading").toString} " +
      s"${fansi.Color.Yellow(label).toString} ${bar.styled} " +
      fansi.Color.Cyan(bytes).toString
    ProgressLine(plain, styled)

  private def renderCompletedLine(
      url: String,
      downloadedBytes: Long,
      totalBytes: Option[Long]
  ): ProgressLine =
    val label  = fileName(url)
    val bar    = progressBar(downloadedBytes, totalBytes)
    val bytes  = byteText(downloadedBytes, totalBytes)
    val plain  = s"✅ completed $label ${bar.plain} $bytes"
    val styled = fansi.Color.Green(s"✅ completed $label").toString +
      s" ${bar.styled} ${fansi.Color.Green(bytes).toString}"
    ProgressLine(plain, styled)

  private def renderInPlace(line: ProgressLine): Unit =
    val padding = " " * (activeLineLength - line.visibleLength).max(0)
    out.print(s"\r${line.styled}$padding")
    out.flush()
    activeLineLength = line.visibleLength

  private def renderCompleted(line: ProgressLine): Unit =
    val padding = " " * (activeLineLength - line.visibleLength).max(0)
    out.print(s"\r${line.styled}$padding\n")
    out.flush()
    activeLineLength = 0

  private def progressBar(downloadedBytes: Long, totalBytes: Option[Long]): ProgressLine =
    totalBytes.filter(_ > 0L) match
      case Some(total) =>
        val ratio  = (downloadedBytes.toDouble / total.toDouble).max(0.0).min(1.0)
        val filled = (ratio * width).round.toInt
        val pct    = (ratio * 100.0).round.toInt
        val empty  = width - filled
        val plain  = s"[${"█" * filled}${"░" * empty}] $pct%"
        val styled = s"[${barColor(ratio)("█" * filled).toString}" +
          s"${fansi.Color.Blue("░" * empty).toString}] ${percentColor(pct)(s"$pct%").toString}"
        ProgressLine(plain, styled)
      case None =>
        val plain = s"[${"█" * width}]"
        ProgressLine(plain, fansi.Color.Magenta(plain).toString)

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

  private def fileName(url: String): String =
    val fallback = "download"
    scala.util.Try(URI.create(url).getPath)
      .toOption
      .flatMap(path => Option(path).map(_.split('/').toVector.filter(_.nonEmpty).lastOption))
      .flatten
      .map(RenderSafety.terminalLine(_))
      .getOrElse(fallback)

private[cli] final case class ProgressLine(plain: String, styled: String):
  def visibleLength: Int = plain.length

private[cli] object CliApplyOutput:

  def colorLines(lines: Vector[String]): Vector[String] = lines.map(colorLine)

  private def colorLine(line: String): String =
    if line.startsWith("installed ") then fansi.Color.Green(line).toString
    else if line.startsWith("failed ") then fansi.Color.Red(line).toString
    else line

  def summary(event: InstallerEvent.Summary): Vector[String] =
    val status =
      if event.status == InstallerRunStatus.Succeeded then
        fansi.Color.Green("🎉 apply completed successfully").toString
      else fansi.Color.Red("💥 apply finished with errors").toString
    Vector(
      "",
      fansi.Color.Magenta("✨ Summary").toString,
      s"  ${fansi.Color.Green(s"✅ installed: ${event.installed}").toString}",
      s"  ${fansi.Color.Red(s"❌ failed: ${event.failed}").toString}",
      s"  ${fansi.Color.Yellow(s"⏭ skipped: ${event.skipped}").toString}",
      s"  ${fansi.Color.Cyan(s"🚦 exit code: ${event.exitCode}").toString}",
      s"  $status"
    )
