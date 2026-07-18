package binstaller.cli

private[cli] enum CliOutputStyle:
  case Ansi, Plain

  def supportsAnsi: Boolean = this == CliOutputStyle.Ansi

  def color(value: String)(style: fansi.Attrs): String =
    if supportsAnsi then style(value).toString else value

private[cli] object CliOutputStyle:

  def forProcessOutput: CliOutputStyle =
    forProcessOutput(sys.env, interactive = Option(System.console()).nonEmpty)

  /** Testable seam: `System.console()` is non-null only when both stdin and stdout are TTYs, so a
   *  real output terminal loses color when stdin is redirected. The force-color escape hatches
   *  (CLICOLOR_FORCE / FORCE_COLOR, per the ecosystem convention) let users opt back in; NO_COLOR
   *  and TERM=dumb still win. */
  private[cli] def forProcessOutput(
      env: Map[String, String],
      interactive: Boolean
  ): CliOutputStyle =
    val noColor      = env.contains("NO_COLOR")
    val dumbTerminal = env.get("TERM").contains("dumb")
    if !noColor && !dumbTerminal && (interactive || forceColorRequested(env)) then CliOutputStyle.Ansi
    else CliOutputStyle.Plain

  private def forceColorRequested(env: Map[String, String]): Boolean =
    env.get("CLICOLOR_FORCE").exists(value => value != "0") ||
      env.get("FORCE_COLOR").exists(value => value.nonEmpty && value != "0")
