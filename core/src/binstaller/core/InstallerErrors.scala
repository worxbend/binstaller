package binstaller.core

import binstaller.config.ToolName

/** Expected failure while resolving, building, or saving a lock file. */
enum LockCommandError:
  case ResolutionFailed(error: ResolvePlanError)
  case InspectionFailed(toolName: ToolName, message: String)
  case InvalidPath(path: String, message: String)
  case SaveFailed(error: LockFileError)

/** Rendering helpers used by the legacy lock command projection. */
object LockCommandError:

  /** Render a typed lock failure into script-friendly command lines. */
  def renderLines(
      error: LockCommandError,
      redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
  ): Vector[String] = error match
    case LockCommandError.ResolutionFailed(resolveError) =>
      ResolvePlanError.renderLines(resolveError, redactions)
    case LockCommandError.InspectionFailed(toolName, message) =>
      Vector(s"lock inspection failed for tool '$toolName': $message")
    case LockCommandError.InvalidPath(path, message) =>
      Vector(RenderSafety.display(s"lock path '$path' is invalid: $message", redactions))
    case LockCommandError.SaveFailed(lockFileError) => Vector(LockFileError.render(lockFileError))

/** Expected failure before an apply run is allowed to perform side effects. */
enum ApplyPreflightError:
  case SudoSymlinkNotAllowed(toolName: ToolName)

/** Rendering helpers for expected apply preflight failures. */
object ApplyPreflightError:

  /** Render a preflight failure into a concise user-facing line. */
  def render(error: ApplyPreflightError): String = error match
    case ApplyPreflightError.SudoSymlinkNotAllowed(toolName) =>
      ToolInstallError.renderSudoSymlinkNotAllowed(toolName)

/**
 * Expected failure while installing one tool.
 *
 * Every case names the tool it failed for, so that is declared once on the enum rather than
 * recovered afterwards by a match with one arm per case. The parameter is `name` rather than
 * `toolName` because an enum case parameter becomes a val and would clash with the inherited one.
 */
enum ToolInstallError(val toolName: ToolName):

  case DownloadFailed(
      name: ToolName,
      url: String,
      message: String,
      provenance: Option[UrlProvenance] = None
  ) extends ToolInstallError(name)

  case ChecksumMismatch(name: ToolName, expected: String, actual: String, source: String)
      extends ToolInstallError(name)

  case StagingFailed(name: ToolName, message: String) extends ToolInstallError(name)

  case ModeApplicationFailed(name: ToolName, path: String, mode: String, message: String)
      extends ToolInstallError(name)

  case ReplacementFailed(name: ToolName, message: String)       extends ToolInstallError(name)
  case ArchiveExtractionFailed(name: ToolName, message: String) extends ToolInstallError(name)
  case MissingExecutable(name: ToolName, path: String)          extends ToolInstallError(name)

  case SymlinkFailed(name: ToolName, path: String, target: String, message: String)
      extends ToolInstallError(name)

  case SudoSymlinkNotAllowed(name: ToolName) extends ToolInstallError(name)

  case SudoCredentialCanceled(name: ToolName, path: String, target: String)
      extends ToolInstallError(name)

  case SudoCredentialsUnavailable(name: ToolName, path: String, target: String, message: String)
      extends ToolInstallError(name)

/** Rendering and inspection helpers for install failures. */
object ToolInstallError:

  /** Render an install failure with terminal safety and redaction applied. */
  def render(
      error: ToolInstallError,
      redactions: SensitiveValueRedactions
  ): String = error match
    case ToolInstallError.DownloadFailed(toolName, url, message, provenance) => detailBlock(
        s"download: $url: $message",
        Vector("tool" -> toolName.value, "url" -> url, "message" -> message) ++
          redirectDetailPairs("download", provenance) ++
          Vector("suggestion" ->
            ("check the URL resolves and the release asset exists; " +
              "run `binstaller plan` to see the resolved URL")),
        redactions
      )
    case ToolInstallError.ChecksumMismatch(toolName, expected, actual, source) => detailBlock(
        s"checksum: sha256 expected $expected, got $actual",
        Vector(
          "tool"            -> toolName.value,
          "expected sha256" -> expected,
          "actual sha256"   -> actual,
          "checksum source" -> source,
          "suggestion" -> "verify the downloaded artifact before updating the manifest checksum"
        ),
        redactions
      )
    case ToolInstallError.StagingFailed(toolName, message) => detailBlock(
        s"staging: $message",
        Vector(
          "tool"       -> toolName.value,
          "message"    -> message,
          "suggestion" -> "check free space and write permissions on the appsDir parent directory"
        ),
        redactions
      )
    case ToolInstallError.ModeApplicationFailed(toolName, path, mode, message) => detailBlock(
        s"mode: $mode for $path: $message",
        Vector(
          "tool"       -> toolName.value,
          "path"       -> path,
          "mode"       -> mode,
          "message"    -> message,
          "suggestion" -> "check the mode is a four-digit octal string and the staged file exists"
        ),
        redactions
      )
    case ToolInstallError.ReplacementFailed(toolName, message) => detailBlock(
        s"replacement: $message",
        Vector(
          "tool"       -> toolName.value,
          "message"    -> message,
          "suggestion" -> "check free space and write permissions on the appsDir parent directory"
        ),
        redactions
      )
    case ToolInstallError.ArchiveExtractionFailed(toolName, message) => detailBlock(
        s"archive extraction: $message",
        Vector(
          "tool"       -> toolName.value,
          "message"    -> message,
          "suggestion" ->
            ("check spec.plan[].spec.download.archive.type matches the artifact " +
              "and that its file and directory mappings exist inside it")
        ),
        redactions
      )
    case ToolInstallError.MissingExecutable(toolName, path) => detailBlock(
        s"verify executable: missing $path",
        Vector(
          "tool"          -> toolName.value,
          "expected path" -> path,
          "suggestion"    ->
            ("check spec.plan[].spec.executables[].path matches the layout inside " +
              "the downloaded artifact")
        ),
        redactions
      )
    case ToolInstallError.SymlinkFailed(toolName, path, target, message) => detailBlock(
        s"symlink: $path -> $target: $message",
        Vector(
          "tool"       -> toolName.value,
          "path"       -> path,
          "target"     -> target,
          "message"    -> message,
          "suggestion" ->
            ("check the symlink destination directory exists and is writable, " +
              "or set sudo: true with spec.policy.allowSudoSymlinks: true")
        ),
        redactions
      )
    case ToolInstallError.SudoSymlinkNotAllowed(toolName) => renderSudoSymlinkNotAllowed(toolName)
    case ToolInstallError.SudoCredentialCanceled(toolName, path, target) => detailBlock(
        s"sudo credentials canceled for $path -> $target",
        Vector(
          "tool"       -> toolName.value,
          "path"       -> path,
          "target"     -> target,
          "credential" -> "canceled",
          "suggestion" -> "re-run in an interactive terminal and enter the sudo password"
        ),
        redactions
      )
    case ToolInstallError.SudoCredentialsUnavailable(toolName, path, target, message) =>
      detailBlock(
        s"sudo credentials unavailable for $path -> $target",
        Vector(
          "tool"       -> toolName.value,
          "path"       -> path,
          "target"     -> target,
          "credential" -> message,
          "suggestion" ->
            ("re-run from an interactive terminal, or pre-authorize sudo before " +
              "running binstaller")
        ),
        redactions
      )

  /** The one spelling of the sudo-symlink policy refusal, shared with [[ApplyPreflightError]]. */
  private[core] def renderSudoSymlinkNotAllowed(toolName: ToolName): String =
    s"failed $toolName: sudo symlinks are not allowed by policy.allowSudoSymlinks"

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
