package binstaller.core

/** Expected failure before an apply run is allowed to perform side effects. */
enum ApplyPreflightError:
  case SudoSymlinkNotAllowed(toolName: String)

/** Rendering helpers for expected apply preflight failures. */
object ApplyPreflightError:

  /** Render a preflight failure into a concise user-facing line. */
  def render(error: ApplyPreflightError): String = error match
    case ApplyPreflightError.SudoSymlinkNotAllowed(toolName) =>
      s"failed $toolName: sudo symlinks are not allowed by policy.allowSudoSymlinks"

/** Expected failure while installing one tool. */
enum ToolInstallError:

  case DownloadFailed(
      toolName: String,
      url: String,
      message: String,
      provenance: Option[UrlProvenance] = None
  )

  case ChecksumMismatch(toolName: String, expected: String, actual: String, source: String)
  case StagingFailed(toolName: String, message: String)
  case ModeApplicationFailed(toolName: String, path: String, mode: String, message: String)
  case ReplacementFailed(toolName: String, message: String)
  case ArchiveExtractionFailed(toolName: String, message: String)
  case MissingExecutable(toolName: String, path: String)
  case SymlinkFailed(toolName: String, path: String, target: String, message: String)
  case SudoSymlinkNotAllowed(toolName: String)
  case SudoCredentialCanceled(toolName: String, path: String, target: String)
  case SudoCredentialsUnavailable(toolName: String, path: String, target: String, message: String)

/** Rendering and inspection helpers for install failures. */
object ToolInstallError:

  /** Extract the tool name associated with an install failure. */
  def toolName(error: ToolInstallError): String = error match
    case ToolInstallError.DownloadFailed(toolName, _, _, _)             => toolName
    case ToolInstallError.ChecksumMismatch(toolName, _, _, _)           => toolName
    case ToolInstallError.StagingFailed(toolName, _)                    => toolName
    case ToolInstallError.ModeApplicationFailed(toolName, _, _, _)      => toolName
    case ToolInstallError.ReplacementFailed(toolName, _)                => toolName
    case ToolInstallError.ArchiveExtractionFailed(toolName, _)          => toolName
    case ToolInstallError.MissingExecutable(toolName, _)                => toolName
    case ToolInstallError.SymlinkFailed(toolName, _, _, _)              => toolName
    case ToolInstallError.SudoSymlinkNotAllowed(toolName)               => toolName
    case ToolInstallError.SudoCredentialCanceled(toolName, _, _)        => toolName
    case ToolInstallError.SudoCredentialsUnavailable(toolName, _, _, _) => toolName

  /** Render an install failure with terminal safety and redaction applied. */
  def render(
      error: ToolInstallError,
      redactions: SensitiveValueRedactions
  ): String = error match
    case ToolInstallError.DownloadFailed(toolName, url, message, provenance) => detailBlock(
        s"download: $url: $message",
        Vector("tool" -> toolName, "url" -> url, "message" -> message) ++
          redirectDetailPairs("download", provenance),
        redactions
      )
    case ToolInstallError.ChecksumMismatch(toolName, expected, actual, source) => detailBlock(
        s"checksum: sha256 expected $expected, got $actual",
        Vector(
          "tool"            -> toolName,
          "expected sha256" -> expected,
          "actual sha256"   -> actual,
          "checksum source" -> source,
          "suggestion" -> "verify the downloaded artifact before updating the manifest checksum"
        ),
        redactions
      )
    case ToolInstallError.StagingFailed(toolName, message) => detailBlock(
        s"staging: $message",
        Vector("tool" -> toolName, "message" -> message),
        redactions
      )
    case ToolInstallError.ModeApplicationFailed(toolName, path, mode, message) => detailBlock(
        s"mode: $mode for $path: $message",
        Vector("tool" -> toolName, "path" -> path, "mode" -> mode, "message" -> message),
        redactions
      )
    case ToolInstallError.ReplacementFailed(toolName, message) => detailBlock(
        s"replacement: $message",
        Vector("tool" -> toolName, "message" -> message),
        redactions
      )
    case ToolInstallError.ArchiveExtractionFailed(toolName, message) => detailBlock(
        s"archive extraction: $message",
        Vector("tool" -> toolName, "message" -> message),
        redactions
      )
    case ToolInstallError.MissingExecutable(toolName, path) => detailBlock(
        s"verify executable: missing $path",
        Vector("tool" -> toolName, "expected path" -> path),
        redactions
      )
    case ToolInstallError.SymlinkFailed(toolName, path, target, message) => detailBlock(
        s"symlink: $target -> $path: $message",
        Vector("tool" -> toolName, "path" -> path, "target" -> target, "message" -> message),
        redactions
      )
    case ToolInstallError.SudoSymlinkNotAllowed(_) =>
      "sudo symlinks are not allowed by policy.allowSudoSymlinks"
    case ToolInstallError.SudoCredentialCanceled(toolName, path, target) => detailBlock(
        s"sudo credentials canceled for $target -> $path",
        Vector(
          "tool"       -> toolName,
          "path"       -> path,
          "target"     -> target,
          "credential" -> "canceled"
        ),
        redactions
      )
    case ToolInstallError.SudoCredentialsUnavailable(toolName, path, target, message) =>
      detailBlock(
        s"sudo credentials unavailable for $target -> $path",
        Vector(
          "tool"       -> toolName,
          "path"       -> path,
          "target"     -> target,
          "credential" -> message
        ),
        redactions
      )

  private def detailBlock(
      summary: String,
      details: Vector[(String, String)],
      redactions: SensitiveValueRedactions
  ): String =
    val lines = summary +: details.map((name, value) => s"  $name: $value")
    RenderSafety.displayLines(lines, redactions).mkString("\n")

  private def redirectDetailPairs(
      label: String,
      provenance: Option[UrlProvenance]
  ): Vector[(String, String)] = provenance.filter(_.redirected) match
    case Some(value) => Vector(
        s"$label initial url" -> value.initialUrl,
        s"$label final url"   -> value.finalUrl,
        s"$label redirects"   -> UrlProvenance.redirectChainForDisplay(value)
      )
    case None => Vector.empty
