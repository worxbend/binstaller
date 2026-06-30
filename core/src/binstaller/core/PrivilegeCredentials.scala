package binstaller.core

/** Context shown to a user before requesting credentials for a sudo operation. */
final case class SudoCredentialRequest(
    toolName: String,
    destinationPath: String,
    targetPath: String,
    operation: String
)

/** Password supplied by an injected credential boundary. */
final case class SudoPassword private (private[core] val secret: SecretText):
  private[core] def commandInput: CommandInput = CommandInput.SecretLine(secret)

  override def toString: String = "<redacted>"

/** Sudo password constructors. */
object SudoPassword:
  /** Wrap a runtime password while keeping it out of diagnostics and command argv. */
  def fromString(value: String): SudoPassword = SudoPassword(SecretText.fromString(value))

/** Expected credential-request outcomes. */
enum SudoCredentialError:
  case Canceled
  case Unavailable(message: String)

/**
 * Boundary for requesting sudo credentials without letting sudo read directly from the terminal.
 */
trait SudoCredentialProvider:

  /** Request a password for one privileged operation. */
  def requestSudoPassword(
      request: SudoCredentialRequest
  ): Either[SudoCredentialError, SudoPassword]

/** Sudo credential provider constructors. */
object SudoCredentialProvider:

  /** Non-interactive provider used until a caller injects a UI-specific credential boundary. */
  val unavailable: SudoCredentialProvider = request =>
    Left(SudoCredentialError.Unavailable(
      s"sudo credentials required for ${request.operation}, but no credential provider is available"
    ))
