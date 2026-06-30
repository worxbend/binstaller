package binstaller.cli

private[cli] enum CliOutputStyle:
  case Ansi, Plain

  def supportsAnsi: Boolean = this == CliOutputStyle.Ansi

  def color(value: String)(style: fansi.Attrs): String =
    if supportsAnsi then style(value).toString else value

private[cli] object CliOutputStyle:

  def forProcessOutput: CliOutputStyle =
    val noColor = sys.env.contains("NO_COLOR")
    val dumbTerminal = sys.env.get("TERM").contains("dumb")
    if Option(System.console()).nonEmpty && !noColor && !dumbTerminal then CliOutputStyle.Ansi
    else CliOutputStyle.Plain
