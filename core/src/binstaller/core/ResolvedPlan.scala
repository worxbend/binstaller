package binstaller.core

import binstaller.config.AllowSudoSymlinks
import binstaller.config.ArchiveSpec
import binstaller.config.ChecksumAlgorithm
import binstaller.config.ConfigLoadError
import binstaller.config.ExecutableMode
import binstaller.config.PolicyMode
import binstaller.config.SymlinkPrivilege
import binstaller.config.ValidationError

/** Variable-resolution inputs and display redaction policy for manifest resolution. */
final case class ResolutionOptions(
    runtimeVariables: Map[String, String],
    redactions: SensitiveValueRedactions,
    hostPlatform: HostPlatform = HostPlatform.current
)

/** Resolution option constructors. */
object ResolutionOptions:

  /** Build options from explicit runtime variables and derive sensitive-value redactions. */
  def apply(runtimeVariables: Map[String, String]): ResolutionOptions = ResolutionOptions(
    runtimeVariables,
    SensitiveValueRedactions.fromRuntimeVariables(runtimeVariables),
    HostPlatform.current
  )

  /**
   * Build options from a deliberately small, non-secret environment allowlist. Arbitrary process
   * environment values are not exposed to manifest URL interpolation.
   */
  def fromEnvironment(): ResolutionOptions = ResolutionOptions(
    sys.env.view.filterKeys(RuntimeTemplateEnvironment.allowed).toMap
  )

private[core] object RuntimeTemplateEnvironment:

  val allowed: Set[String] = Set(
    "HOME",
    "USER",
    "LOGNAME",
    "SHELL",
    "XDG_CACHE_HOME",
    "XDG_CONFIG_HOME",
    "XDG_DATA_HOME"
  )

/** Normalized host values used to evaluate manifest `when` selectors. */
final case class HostPlatform(osFamily: String, architecture: String)

/** Host-platform detection and normalization. */
object HostPlatform:

  /** Detect the current JVM host using stable manifest-facing names. */
  def current: HostPlatform = HostPlatform(
    normalizeOs(System.getProperty("os.name", "unknown")),
    normalizeArchitecture(System.getProperty("os.arch", "unknown"))
  )

  /** Normalize common operating-system aliases. */
  def normalizeOs(value: String): String = value.trim.toLowerCase match
    case name if name.contains("linux")                          => "linux"
    case name if name.contains("mac") || name.contains("darwin") => "darwin"
    case name if name.contains("windows")                        => "windows"
    case name                                                    => name.replaceAll("\\s+", "-")

  /** Normalize common CPU architecture aliases. */
  def normalizeArchitecture(value: String): String = value.trim.toLowerCase match
    case "amd64" | "x86_64" | "x64"                => "amd64"
    case "aarch64" | "arm64"                       => "arm64"
    case "x86" | "i386" | "i486" | "i586" | "i686" => "386"
    case architecture                              => architecture

/** Resolved install plan after interpolation, version lookup, and validation. */
final case class ResolvedPlan(
    policy: ResolvedPolicy,
    tools: Vector[ResolvedTool],
    redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
)

/** Resolved profile-wide policy used by apply execution. */
final case class ResolvedPolicy(
    appsDir: String,
    stateFile: Option[String],
    allowSudoSymlinks: AllowSudoSymlinks,
    continueOnError: ContinueOnError,
    mode: PolicyMode = PolicyMode.Developer,
    allowDynamicLatestUrls: PolicyAllowance = PolicyAllowance.Allowed,
    allowMissingChecksums: PolicyAllowance = PolicyAllowance.Allowed,
    allowArchiveCandidateFallback: PolicyAllowance = PolicyAllowance.Allowed
)

/** Effective allow/reject decision after applying a manifest policy profile and overrides. */
enum PolicyAllowance:
  case Allowed, Rejected

/** Whether apply should continue after a failed tool. */
enum ContinueOnError:
  case Enabled, Disabled

/** Helpers for converting manifest booleans into continue-on-error policy. */
object ContinueOnError:
  /** Convert a manifest boolean into [[ContinueOnError]]. */
  def fromBoolean(value: Boolean): ContinueOnError = if value then Enabled else Disabled

/** One resolved binary tool ready for rendering or execution. */
final case class ResolvedTool(
    name: String,
    description: Option[String],
    version: ResolvedVersion,
    installDir: String,
    createDirectories: Vector[String],
    download: ResolvedDownload,
    executables: Vector[ResolvedExecutable],
    symlinks: Vector[ResolvedSymlink]
)

/** Tool version after resolution. */
enum ResolvedVersion:
  case Concrete(value: String, provenance: Option[UrlProvenance] = None)
  case DynamicLatestUrl(note: Option[String])

/** Rendering helpers for resolved versions. */
object ResolvedVersion:

  /** Render a resolved version for CLI display. */
  def render(version: ResolvedVersion): String = version match
    case ResolvedVersion.Concrete(value, _)  => value
    case ResolvedVersion.DynamicLatestUrl(_) => "dynamic latest-url"

/** Resolved download fields after interpolation and URL validation. */
final case class ResolvedDownload(
    url: String,
    filename: String,
    checksum: Option[ResolvedChecksum],
    archive: Option[ResolvedArchive]
)

/** Resolved checksum value paired with its provenance for rendering, locking, and diagnostics. */
final case class ResolvedChecksum(
    algorithm: ChecksumAlgorithm,
    value: String,
    source: ResolvedChecksumSource
)

/** How a checksum entered the resolved plan. */
enum ResolvedChecksumSource:
  case Configured

  /**
   * Checksum fetched from a discovery source published alongside the artifact in the same release.
   * This is trust-on-first-use: it catches corruption or truncation in transit but is not an
   * independent integrity guarantee, since anyone who can publish the artifact can publish a
   * matching digest. Prefer a [[Configured]] checksum pinned by the manifest author.
   */
  case Discovered(url: String, file: String, provenance: UrlProvenance)

/** Rendering helpers for resolved checksum provenance. */
object ResolvedChecksum:

  /** Whether the checksum came from an explicit manifest value. */
  def isConfigured(checksum: ResolvedChecksum): Boolean = checksum.source ==
    ResolvedChecksumSource.Configured

  /** Whether the checksum was fetched from a typed discovery source. */
  def isDiscovered(checksum: ResolvedChecksum): Boolean = checksum.source match
    case ResolvedChecksumSource.Discovered(_, _, _) => true
    case ResolvedChecksumSource.Configured          => false

  /** Render the checksum source for user-facing diagnostics. */
  def sourceDescription(checksum: ResolvedChecksum): String = checksum.source match
    case ResolvedChecksumSource.Configured                        => "configured in manifest"
    case ResolvedChecksumSource.Discovered(url, file, provenance) =>
      s"discovered from $url for $file" + UrlProvenance.redirectSuffix(Some(provenance)) +
        "; trust-on-first-use: fetched from the same release, not independent integrity"

/** Resolved archive mappings paired with the original archive declaration. */
final case class ResolvedArchive(
    original: ArchiveSpec,
    files: Vector[ResolvedExtractMapping],
    directories: Vector[ResolvedExtractMapping]
)

/** Resolved archive source to install-target mapping. */
final case class ResolvedExtractMapping(from: String, to: String)

/** Resolved executable path and optional manifest mode. */
final case class ResolvedExecutable(path: String, mode: Option[ExecutableMode])

/** Resolved symlink path, target, and privilege boundary. */
final case class ResolvedSymlink(path: String, target: String, privilege: SymlinkPrivilege)

/** Expected failure while loading, resolving, or selecting a plan. */
enum ResolvePlanError:
  case ConfigLoadFailed(error: ConfigLoadError)
  case ValidationFailed(errors: Vector[ValidationError])
  case SelectionFailed(messages: Vector[String])

/** Rendering helpers for plan-resolution failures. */
object ResolvePlanError:

  /** Render resolution failures into scrubbed user-facing lines. */
  def renderLines(error: ResolvePlanError): Vector[String] = error match
    case ResolvePlanError.ConfigLoadFailed(loadError) => renderConfigLoadError(loadError)
    case ResolvePlanError.ValidationFailed(errors)    =>
      errors.map(error => RenderSafety.display(s"${error.path}: ${error.message}"))
    case ResolvePlanError.SelectionFailed(messages) =>
      messages.map(message => RenderSafety.display(s"selection: $message"))

  private def renderConfigLoadError(error: ConfigLoadError): Vector[String] = error match
    case ConfigLoadError.ValidationFailed(errors) =>
      errors.map(error => RenderSafety.display(s"${error.path}: ${error.message}"))
    case ConfigLoadError.ReadFailed(path, message) =>
      Vector(RenderSafety.display(s"config read failed for $path: $message"))
    case ConfigLoadError.ParseFailed(message) =>
      Vector(RenderSafety.display(s"config parse failed: $message"))
