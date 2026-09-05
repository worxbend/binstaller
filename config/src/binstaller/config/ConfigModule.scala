package binstaller.config

import java.nio.file.Path
import scala.util.Try

/** Public entrypoint for loading binstaller YAML profiles into typed manifest models. */
object ConfigModule:

  /** Load and validate a profile from a filesystem path string. */
  def load(path: String): Either[ConfigLoadError, BinaryDistributionProfile] =
    Try(Path.of(path)).toEither
      .left.map(error =>
        ConfigLoadError.ParseFailed(
          s"invalid config path: ${Diagnostics.describe(error)}"
        )
      )
      .flatMap(ConfigLoader.load)

  /** Load and validate a profile from a filesystem path. */
  def load(path: Path): Either[ConfigLoadError, BinaryDistributionProfile] = ConfigLoader.load(path)

  /** Load and validate a profile from raw YAML text. */
  def loadString(yaml: String): Either[ConfigLoadError, BinaryDistributionProfile] =
    ConfigLoader.loadString(yaml)

/** Expected failures while reading, parsing, or validating a binstaller profile. */
enum ConfigLoadError:
  case ReadFailed(path: Path, message: String)
  case ParseFailed(message: String)
  case ValidationFailed(errors: Vector[ValidationError])

/** A manifest validation error with a YAML-like path suitable for CLI display. */
final case class ValidationError(path: String, message: String)

/** Root manifest for the supported binary-distribution profile schema. */
final case class BinaryDistributionProfile private[config] (
    apiVersion: ApiVersion,
    kind: ManifestKind,
    metadata: ManifestMetadata,
    spec: ProfileSpec
)

/** Validated construction for profiles supplied without the YAML loader. */
object BinaryDistributionProfile:

  /** Build a profile only when its cross-field invariants hold. */
  def validated(
      apiVersion: ApiVersion,
      kind: ManifestKind,
      metadata: ManifestMetadata,
      spec: ProfileSpec
  ): Either[Vector[ValidationError], BinaryDistributionProfile] =
    val profile = BinaryDistributionProfile(apiVersion, kind, metadata, spec)
    val errors  = ProfileValidator.validate(profile)
    if errors.isEmpty then Right(profile) else Left(errors)

/** Supported manifest API versions. */
enum ApiVersion(val value: String):
  case V1Alpha1 extends ApiVersion("binstaller.io/v1alpha1")

/** Supported manifest kinds. */
enum ManifestKind(val value: String):
  case BinaryDistributionProfile extends ManifestKind("BinaryDistributionProfile")

/** Human and machine metadata attached to a profile. */
final case class ManifestMetadata(
    name: String,
    labels: Map[String, String],
    annotations: Map[String, String]
)

/** Install policy, variables, version sources, and ordered plan entries. */
final case class ProfileSpec(
    policy: InstallPolicy,
    vars: Map[String, String],
    versions: Map[String, VersionSource],
    plan: Vector[PlanEntry]
)

/** Profile-wide execution policy decoded from `spec.policy`. */
final case class InstallPolicy(
    mode: PolicyMode,
    continueOnError: PolicyOverride,
    appsDir: String,
    allowSudoSymlinks: PolicyOverride,
    allowDynamicLatestUrls: Option[PolicyOverride],
    allowMissingChecksums: Option[PolicyOverride],
    stateFile: Option[String]
)

/** Coarse policy profile for security-sensitive manifest defaults. */
enum PolicyMode(val value: String):
  case Developer extends PolicyMode("developer")
  case Strict    extends PolicyMode("strict")

/**
 * An explicit yes/no decision read from a `spec.policy` boolean.
 *
 * Named rather than `Boolean` so a call site says which way round it is, and shared by every policy
 * flag rather than given a bespoke type per field: three identical two-case enums with identical
 * `fromBoolean` helpers said nothing a single one does not.
 */
enum PolicyOverride:
  case Enabled, Disabled

/** Helpers for converting YAML booleans into explicit policy overrides. */
object PolicyOverride:
  /** Convert `true` to [[Enabled]] and `false` to [[Disabled]]. */
  def fromBoolean(value: Boolean): PolicyOverride = if value then Enabled else Disabled

/** A declared source for a tool version. */
enum VersionSource:
  case Pinned(value: String)
  case Dynamic(kind: DynamicVersionKind, note: Option[String])
  case Resolver(kind: VersionResolverKind, url: String)

/** Dynamic version source kinds that cannot be reduced to a concrete version at plan time. */
enum DynamicVersionKind(val value: String):
  case LatestUrl extends DynamicVersionKind("latest-url")

/** Network-backed version resolver kinds. */
enum VersionResolverKind(val value: String):
  case HttpText extends VersionResolverKind("http-text")

/** One ordered item in `spec.plan`. */
final case class PlanEntry(
    name: ToolName,
    kind: PlanKind,
    description: Option[String],
    when: Option[WhenClause],
    spec: BinaryToolSpec
)

/** Supported plan entry kinds. */
enum PlanKind(val value: String):
  case BinaryTool extends PlanKind("binary-tool")

/** Optional host selectors for a plan entry. */
final case class WhenClause(os: Option[OsClause], architecture: Option[String])

/** Optional operating-system selector. */
final case class OsClause(family: Option[String])

/** Binary tool install specification after schema decoding, before interpolation. */
final case class BinaryToolSpec(
    versionRef: String,
    installDir: String,
    createDirectories: Vector[String],
    download: DownloadSpec,
    executables: Vector[ExecutableSpec],
    symlinks: Vector[SymlinkSpec]
)

/** Download location, local filename, checksum, and optional archive extraction plan. */
final case class DownloadSpec(
    url: String,
    filename: String,
    checksum: Option[ChecksumSpec],
    archive: Option[ArchiveSpec]
)

/** The one source from which an expected checksum is obtained. */
enum ChecksumSource:
  case Literal(value: Sha256Digest)
  case Discovery(spec: ChecksumDiscoverySpec)

/** Declared checksum source. Current validation supports SHA-256 only. */
final case class ChecksumSpec(
    algorithm: ChecksumAlgorithm,
    source: ChecksumSource
):

  /** Literal checksum value, when this declaration pins one directly. */
  def value: Option[Sha256Digest] = source match
    case ChecksumSource.Literal(value) => Some(value)
    case ChecksumSource.Discovery(_)   => None

  /** Discovery declaration, when the checksum comes from a published checksum file. */
  def discover: Option[ChecksumDiscoverySpec] = source match
    case ChecksumSource.Literal(_)        => None
    case ChecksumSource.Discovery(source) => Some(source)

/** Constructors for valid checksum declarations. */
object ChecksumSpec:

  /** Build a literal checksum declaration, preserving the original two-argument API. */
  def apply(algorithm: ChecksumAlgorithm, value: Sha256Digest): ChecksumSpec =
    ChecksumSpec(algorithm, ChecksumSource.Literal(value))

  /** Build a checksum declaration backed by a published checksum file. */
  def discovery(
      algorithm: ChecksumAlgorithm,
      source: ChecksumDiscoverySpec
  ): ChecksumSpec = ChecksumSpec(algorithm, ChecksumSource.Discovery(source))

/** Supported checksum algorithms. */
enum ChecksumAlgorithm(val value: String):
  case Sha256 extends ChecksumAlgorithm("sha256")

/** Typed checksum discovery source that reads a published checksum file. */
final case class ChecksumDiscoverySpec(
    kind: ChecksumDiscoveryKind,
    url: String,
    file: Option[String]
)

/** Supported published checksum file formats. */
enum ChecksumDiscoveryKind(val value: String):
  case Sha256Sum extends ChecksumDiscoveryKind("sha256sum")

/** Archive extraction specification for downloaded artifacts. */
final case class ArchiveSpec(archiveType: ArchiveType, extract: ArchiveExtract)

/** Supported archive formats. */
enum ArchiveType(val value: String):
  case Zip   extends ArchiveType("zip")
  case TarGz extends ArchiveType("tar.gz")
  case TarXz extends ArchiveType("tar.xz")

/** File and directory mappings selected from an archive. */
final case class ArchiveExtract(
    files: Vector[ExtractMapping],
    directories: Vector[ExtractMapping]
)

/** Relative archive source to relative install-target mapping. */
final case class ExtractMapping(from: String, to: String)

/** Executable path inside an installed tool and its optional POSIX mode. */
final case class ExecutableSpec(path: String, mode: Option[ExecutableMode])

/** Four-digit octal executable mode accepted from the manifest. */
final case class ExecutableMode private (value: String)

/** Executable mode validation and construction. */
object ExecutableMode:

  /**
   * Parse a four-digit octal mode such as `0755`.
   *
   * The constructor is private so the "four-digit octal" invariant is guaranteed rather than merely
   * documented. `core` parses this value with `Integer.parseInt(value, 8)`, which throws
   * `NumberFormatException` on anything else -- an unchecked crash midway through an apply, in a
   * codebase that returns `Either` for every other expected failure.
   */
  def fromString(value: String): Either[String, ExecutableMode] =
    if value.matches("0[0-7]{3}") then Right(ExecutableMode(value))
    else Left("mode must be a four-digit octal string")

/** Whether a symlink is created by the user process or through the sudo boundary. */
enum SymlinkPrivilege:
  case User, Sudo

/** Helpers for converting YAML booleans into explicit symlink privilege. */
object SymlinkPrivilege:
  /** Convert `true` to [[Sudo]] and `false` to [[User]]. */
  def fromBoolean(value: Boolean): SymlinkPrivilege = if value then Sudo else User

/** Symlink declaration from a resolved executable target to an exposed path. */
final case class SymlinkSpec(path: String, target: String, privilege: SymlinkPrivilege)
