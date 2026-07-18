package binstaller.config

/** A tool name safe for diagnostics, state keys, and filesystem-oriented workflows. */
final case class ToolName private (value: String)

object ToolName:

  def fromString(value: String): Either[String, ToolName] =
    if value.trim.isEmpty then Left("tool name must not be empty")
    else if value.exists(Character.isISOControl) then
      Left("tool name must not contain control characters")
    else if value.contains('/') || value.contains('\\') then
      Left("tool name must not contain path separators")
    else if value == "." || value == ".." then Left("tool name must not be a traversal segment")
    else Right(ToolName(value))
