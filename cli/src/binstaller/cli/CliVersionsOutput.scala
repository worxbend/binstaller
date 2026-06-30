package binstaller.cli

private[cli] object CliVersionsOutput:

  def colorLines(lines: Vector[String]): Vector[String] = lines match
    case title +: tableLines => fansi.Bold.On(fansi.Color.Magenta(title)).toString +:
        colorTable(tableLines)
    case empty => empty

  private def colorTable(lines: Vector[String]): Vector[String] =
    val rows   = lines.map(VersionOutputRow.parse)
    val layout = VersionOutputLayout.fromRows(rows)
    rows.zipWithIndex.map:
      case (row, 0)     => colorHeader(row, layout)
      case (row, index) => colorToolRow(row, layout, index)

  private def colorHeader(row: VersionOutputRow, layout: VersionOutputLayout): String =
    s"${fansi.Bold.On(fansi.Color.Cyan(row.paddedPackage(layout))).toString}  " +
      s"${fansi.Bold.On(fansi.Color.Cyan(row.paddedVersion(layout))).toString}  " +
      fansi.Bold.On(fansi.Color.Cyan(row.newerVersion)).toString

  private def colorToolRow(
      row: VersionOutputRow,
      layout: VersionOutputLayout,
      index: Int
  ): String =
    val packageColor = if index % 2 == 0 then fansi.Color.Blue else fansi.Color.Cyan
    s"${packageColor(row.paddedPackage(layout)).toString}  " +
      s"${fansi.Color.Yellow(row.paddedVersion(layout)).toString}  " +
      colorNewerVersion(row.newerVersion)

  private def colorNewerVersion(value: String): String =
    if value == "-" then fansi.Color.Blue(value).toString
    else fansi.Color.Green(value).toString

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
