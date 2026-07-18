package binstaller.cli

private[cli] object CliVersionsOutput:

  def colorLines(
      lines: Vector[String],
      outputStyle: CliOutputStyle = CliOutputStyle.Ansi
  ): Vector[String] = lines match
    case title +: tableLines => boldColor(title, fansi.Color.Magenta, outputStyle) +:
        colorTable(tableLines, outputStyle)
    case empty => empty

  private def colorTable(lines: Vector[String], outputStyle: CliOutputStyle): Vector[String] =
    val rows   = lines.map(VersionOutputRow.parse)
    val layout = VersionOutputLayout.fromRows(rows)
    rows.zipWithIndex.map:
      case (row, 0)     => colorHeader(row, layout, outputStyle)
      case (row, index) => colorToolRow(row, layout, index, outputStyle)

  private def colorHeader(
      row: VersionOutputRow,
      layout: VersionOutputLayout,
      outputStyle: CliOutputStyle
  ): String = s"${boldColor(row.paddedPackage(layout), fansi.Color.Cyan, outputStyle)}  " +
    s"${boldColor(row.paddedVersion(layout), fansi.Color.Cyan, outputStyle)}  " +
    boldColor(row.newerVersion, fansi.Color.Cyan, outputStyle)

  private def colorToolRow(
      row: VersionOutputRow,
      layout: VersionOutputLayout,
      index: Int,
      outputStyle: CliOutputStyle
  ): String =
    val packageColor = if index % 2 == 0 then fansi.Color.Blue else fansi.Color.Cyan
    s"${outputStyle.color(row.paddedPackage(layout))(packageColor)}  " +
      s"${outputStyle.color(row.paddedVersion(layout))(fansi.Color.Yellow)}  " +
      colorNewerVersion(row.newerVersion, outputStyle)

  private def colorNewerVersion(value: String, outputStyle: CliOutputStyle): String =
    if value == "-" then outputStyle.color(value)(fansi.Color.Blue)
    else if value == "?" then outputStyle.color(value)(fansi.Color.Yellow)
    else outputStyle.color(value)(fansi.Color.Green)

  private def boldColor(
      value: String,
      color: fansi.Attrs,
      outputStyle: CliOutputStyle
  ): String = if outputStyle.supportsAnsi then fansi.Bold.On(color(value)).toString else value

private[cli] final case class VersionOutputRow(
    packageName: String,
    version: String,
    newerVersion: String
):

  def paddedPackage(layout: VersionOutputLayout): String =
    packageName.padTo(layout.packageWidth, ' ')

  def paddedVersion(layout: VersionOutputLayout): String = version.padTo(layout.versionWidth, ' ')

private[cli] object VersionOutputRow:

  def parse(line: String): VersionOutputRow = line.split(" {2,}", 3).toVector match
    case Vector(packageName, version, newerVersion) => VersionOutputRow(
        packageName,
        version,
        newerVersion
      )
    case _ => VersionOutputRow(line, "", "")

private[cli] final case class VersionOutputLayout(packageWidth: Int, versionWidth: Int)

private[cli] object VersionOutputLayout:

  def fromRows(rows: Vector[VersionOutputRow]): VersionOutputLayout = VersionOutputLayout(
    rows.map(_.packageName.length).maxOption.getOrElse(0),
    rows.map(_.version.length).maxOption.getOrElse(0)
  )
