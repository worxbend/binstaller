package binstaller.cli

import binstaller.core.InstallerResult
import binstaller.core.NewerVersionStatus
import binstaller.core.VersionSummaryRow

/**
 * Colours the `versions` table.
 *
 * Built from the structured rows core returns, not from core's rendered text. Recovering columns by
 * splitting the padded lines on runs of two-or-more spaces — which is what this did before —
 * mis-parses any package name or version containing two consecutive spaces, and cannot tell the `-`
 * / `?` sentinels from a release tag spelled the same way.
 */
private[cli] object CliVersionsOutput:

  def colorLines(
      result: InstallerResult,
      outputStyle: CliOutputStyle = CliOutputStyle.Ansi
  ): Vector[String] = result.lines match
    case title +: _ if result.versionRows.nonEmpty =>
      boldColor(title, fansi.Color.Magenta, outputStyle) +:
        colorTable(result.versionRows, outputStyle)
    case title +: _ => boldColor(title, fansi.Color.Magenta, outputStyle) +: result.lines.drop(1)
    case empty      => empty

  private def colorTable(
      rows: Vector[VersionSummaryRow],
      outputStyle: CliOutputStyle
  ): Vector[String] =
    val header =
      VersionSummaryRow("package", "version", NewerVersionStatus.Available("newer version"))
    // The header participates in the width computation exactly as it does in core, so the two
    // tables line up column for column.
    val layout = layoutFor(header +: rows)
    colorHeader(header, layout, outputStyle) +: rows.zipWithIndex.map: (row, index) =>
      colorToolRow(row, layout, index, outputStyle)

  private final case class Layout(packageWidth: Int, versionWidth: Int)

  private def layoutFor(rows: Vector[VersionSummaryRow]): Layout = Layout(
    rows.map(_.packageName.length).maxOption.getOrElse(0),
    rows.map(_.version.length).maxOption.getOrElse(0)
  )

  private def colorHeader(
      row: VersionSummaryRow,
      layout: Layout,
      outputStyle: CliOutputStyle
  ): String =
    val packageCell = row.packageName.padTo(layout.packageWidth, ' ')
    val versionCell = row.version.padTo(layout.versionWidth, ' ')
    s"${boldColor(packageCell, fansi.Color.Cyan, outputStyle)}  " +
      s"${boldColor(versionCell, fansi.Color.Cyan, outputStyle)}  " +
      boldColor(NewerVersionStatus.render(row.newer), fansi.Color.Cyan, outputStyle)

  private def colorToolRow(
      row: VersionSummaryRow,
      layout: Layout,
      index: Int,
      outputStyle: CliOutputStyle
  ): String =
    val packageColor = if index % 2 == 0 then fansi.Color.Blue else fansi.Color.Cyan
    s"${outputStyle.color(row.packageName.padTo(layout.packageWidth, ' '))(packageColor)}  " +
      s"${outputStyle.color(row.version.padTo(layout.versionWidth, ' '))(fansi.Color.Yellow)}  " +
      colorNewerVersion(row.newer, outputStyle)

  private def colorNewerVersion(status: NewerVersionStatus, outputStyle: CliOutputStyle): String =
    val text = NewerVersionStatus.render(status)
    status match
      case NewerVersionStatus.UpToDate     => outputStyle.color(text)(fansi.Color.Blue)
      case NewerVersionStatus.Unknown      => outputStyle.color(text)(fansi.Color.Yellow)
      case NewerVersionStatus.Available(_) => outputStyle.color(text)(fansi.Color.Green)

  private def boldColor(
      value: String,
      color: fansi.Attrs,
      outputStyle: CliOutputStyle
  ): String = if outputStyle.supportsAnsi then fansi.Bold.On(color(value)).toString else value
