package binstaller.config

/** A tool name safe for diagnostics, state keys, and filesystem-oriented workflows.
 *
 *  Tool name is an identity, not a label: it keys apply-state rows, lock-file rows, event
 *  correlation and `--only` / `--skip` selection. Carrying it as a bare `String` meant the
 *  invariant held only because one validator happened to run before anything used it — the type
 *  existed and its single call site discarded the value it constructed, keeping only the error.
 */
final case class ToolName private (value: String):

  /** Renders as the bare name, so `s"tool '$toolName'"` reads unchanged. */
  override def toString: String = value

/** Tool name validation and construction. */
object ToolName:

  /** Parse a tool name, rejecting names that are unsafe as a path segment or a diagnostic. */
  def fromString(value: String): Either[String, ToolName] =
    if value.trim.isEmpty then Left("tool name must not be empty")
    else if value.exists(Character.isISOControl) then
      Left("tool name must not contain control characters")
    else if value.contains('/') || value.contains('\\') then
      Left("tool name must not contain path separators")
    else if value == "." || value == ".." then Left("tool name must not be a traversal segment")
    else Right(ToolName(value))

  /** Placeholder used by the decoder when a name is rejected and decoding must still produce a
   *  value to keep accumulating the rest of the manifest's errors. */
  private[config] val invalidSentinel: ToolName = ToolName("<invalid>")

  /** Wrap a name this program already validated. */
  private[binstaller] def unsafe(value: String): ToolName = ToolName(value)
