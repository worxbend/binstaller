package binstaller.tui

import binstaller.core.RenderSafety
import binstaller.core.SensitiveValueRedactions
import binstaller.core.SudoCredentialError
import binstaller.core.SudoCredentialProvider
import binstaller.core.SudoCredentialRequest
import binstaller.core.SudoPassword

/** Redacted password-prompt payload safe to keep in render-only modal state. */
final case class TuiPasswordPromptView(
    operation: String,
    toolName: String,
    destinationPath: Option[String],
    targetPath: Option[String],
    maskedLength: Int
)

/** TUI password-prompt payload constructors. */
object TuiPasswordPromptView:

  /** Convert a core credential request into display-only prompt fields. */
  def fromRequest(
      request: SudoCredentialRequest,
      maskedLength: Int,
      redactions: SensitiveValueRedactions
  ): TuiPasswordPromptView = TuiPasswordPromptView(
    operation = RenderSafety.display(request.operation, redactions),
    toolName = RenderSafety.display(request.toolName, redactions),
    destinationPath = nonBlank(request.destinationPath).map(RenderSafety.display(_, redactions)),
    targetPath = nonBlank(request.targetPath).map(RenderSafety.display(_, redactions)),
    maskedLength = maskedLength.max(0)
  )

  private def nonBlank(value: String): Option[String] = Option(value).map(_.trim).filter(_.nonEmpty)

/** Terminal-backed sudo credential provider that never stores password text in TUI state. */
private[tui] final class TerminalSudoCredentialProvider(
    terminal: TuiTerminal,
    redactions: SensitiveValueRedactions
) extends SudoCredentialProvider:

  def requestSudoPassword(
      request: SudoCredentialRequest
  ): Either[SudoCredentialError, SudoPassword] =
    var password = ""
    try
      var result: Option[Either[SudoCredentialError, SudoPassword]] = None
      while result.isEmpty do
        renderPrompt(request, password.length)
        terminal.readInput() match
          case Some(TuiInput.Enter) if password == "/cancel" =>
            result = Some(Left(SudoCredentialError.Canceled))
          case Some(TuiInput.Enter) => result = Some(Right(SudoPassword.fromString(password)))
          case Some(TuiInput.Escape | TuiInput.CtrlC | TuiInput.Quit) =>
            result = Some(Left(SudoCredentialError.Canceled))
          case Some(TuiInput.Backspace) => password = password.dropRight(1)
          case Some(TuiInput.Slash)     => password = password + "/"
          case Some(TuiInput.Question)  => password = password + "?"
          case Some(TuiInput.Character(value)) if !value.isControl => password = password + value
          case Some(TuiInput.Resize(_))                            => ()
          case Some(_)                                             => ()
          case None => result = Some(Left(SudoCredentialError.Canceled))
      result.getOrElse(Left(SudoCredentialError.Canceled))
    finally password = ""

  private def renderPrompt(request: SudoCredentialRequest, maskedLength: Int): Unit =
    val viewport = terminal.viewport
    val width    = viewport.width.max(1)
    val prompt   = TuiPasswordPromptView.fromRequest(request, maskedLength, redactions)
    val frame    = Vector(
      PlanningTuiStatus.style(
        PlanningTuiStatus.Active,
        fit("binstaller tui | privileged operation", width)
      ),
      fit("sudo credentials are required for the current apply operation", width),
      ""
    ) ++ TuiModalRenderer.render(Some(TuiModal.PasswordPrompt(prompt)), width)
    terminal.render(frame.take(viewport.height.max(1)))

  private def fit(value: String, width: Int): String =
    val safe = RenderSafety.terminalLine(value, redactions)
    if safe.length <= width then safe + (" " * (width - safe.length).max(0))
    else if width == 1 then "."
    else safe.take(width - 1) + "."
