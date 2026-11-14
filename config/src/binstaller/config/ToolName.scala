package binstaller.config

/**
 * A tool name safe for diagnostics, state keys, and filesystem-oriented workflows.
 *
 * Tool name is an identity, not a label: it keys apply-state rows, lock-file rows, event
 * correlation and `--only` / `--skip` selection. Carrying it as a bare `String` meant the invariant
 * held only because one validator happened to run before anything used it — the type existed and
 * its single call site discarded the value it constructed, keeping only the error.
 */
final case class ToolName private (value: String):

  /** Renders as the bare name, so `s"tool '$toolName'"` reads unchanged. */
  override def toString: String = value

/** Tool name validation and construction. */
object ToolName:

  /**
   * Parse a tool name, rejecting names that are unsafe as a path segment or a diagnostic, and names
   * that `--only` / `--skip` cannot address.
   */
  def fromString(value: String): Either[String, ToolName] =
    unsafeNameReason(value).orElse(unselectableNameReason(value)) match
      case Some(reason) => Left(s"tool name $reason")
      case None         => Right(ToolName(value))

  /**
   * Why `value` is unsafe as a path segment or a diagnostic, worded without the noun.
   *
   * The profile name shares this rule but is not a tool name, so each caller names its own subject.
   */
  private[config] def unsafeNameReason(value: String): Option[String] =
    if value.trim.isEmpty then Some("must not be empty")
    else if value.exists(Character.isISOControl) then Some("must not contain control characters")
    else if value.contains('/') || value.contains('\\') then
      Some("must not contain path separators")
    else if value == "." || value == ".." then Some("must not be a traversal segment")
    else None

  // The CLI splits `--only` / `--skip` values on commas and whitespace, and picocli reads a value
  // starting with `-` as an option, so such a name would load and then never be selectable.
  private def unselectableNameReason(value: String): Option[String] =
    val hasSeparator = value.exists(char =>
      char == ',' || Character.isWhitespace(char) || Character.isSpaceChar(char)
    )
    Option.when(hasSeparator || value.startsWith("-")):
      "must not start with '-' or contain whitespace or commas, " +
        "so that --only and --skip can select it"

  /**
   * Placeholder used by the decoder when a name is rejected and decoding must still produce a value
   * to keep accumulating the rest of the manifest's errors.
   */
  private[config] val invalidSentinel: ToolName = ToolName("<invalid>")

  /** Wrap a name this program already validated. */
  private[binstaller] def trusted(value: String): ToolName = ToolName(value)
