package binstaller.core

import binstaller.config.Sha256Digest
import binstaller.config.ToolName

import java.nio.file.Path

/** Whether plan or apply should require a compatible lock file. */
enum LockedApplyMode:
  case Enabled, Disabled

/** Helpers for converting CLI flags into locked-apply mode. */
object LockedApplyMode:
  /** Convert a boolean CLI flag into [[LockedApplyMode]]. */
  def fromFlag(value: Boolean): LockedApplyMode = if value then Enabled else Disabled

/** Validated lock metadata visible to plan renderers. */
final case class LockedApplyProvenance(path: Path, tools: Map[ToolName, LockFileTool])

/** Expected locked-apply gate failure. */
enum LockedApplyError:
  case LockFile(error: LockFileError)
  case Incompatible(path: Path, message: String)

/** Rendering helpers for locked-apply failures. */
object LockedApplyError:

  /** Render a locked-apply failure into concise user-facing lines. */
  def renderLines(
      error: LockedApplyError,
      redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
  ): Vector[String] = error match
    case LockedApplyError.LockFile(lockError) =>
      Vector(RenderSafety.display(LockFileError.render(lockError), redactions))
    case LockedApplyError.Incompatible(path, message) =>
      Vector(RenderSafety.display(s"locked apply refused by $path: $message", redactions))

private[core] object LockedApplyValidator:

  private enum LockedVersion:
    case Concrete(value: String, provenance: Option[UrlProvenance])
    case Dynamic

  private object LockedVersion:

    def parse(toolName: ToolName, tool: LockFileTool): Either[String, LockedVersion] =
      if tool.dynamicSource then
        if tool.resolvedVersion.nonEmpty || tool.versionProvenance.nonEmpty then
          Left(s"tool '$toolName' dynamic lock has unexpected concrete version fields")
        else Right(LockedVersion.Dynamic)
      else
        tool.resolvedVersion match
          case Some(value) => Right(LockedVersion.Concrete(value, tool.versionProvenance))
          case None        => Left(s"tool '$toolName' concrete lock has no resolved version")

  def validate(
      prepared: PreparedPlan,
      lockPath: Path,
      lockFileStore: LockFileStore,
      metadataClient: BinaryMetadataClient
  ): Either[LockedApplyError, LockedApplyProvenance] =
    val normalized = lockPath.toAbsolutePath.normalize()
    lockFileStore.load(lockPath) match
      case Left(error)     => Left(LockedApplyError.LockFile(error))
      case Right(lockFile) => validateLoaded(prepared, normalized, lockFile, metadataClient)

  private def validateLoaded(
      prepared: PreparedPlan,
      path: Path,
      lockFile: LockFile,
      metadataClient: BinaryMetadataClient
  ): Either[LockedApplyError, LockedApplyProvenance] =
    firstProblem(prepared, lockFile, metadataClient) match
      case Some(message) => Left(LockedApplyError.Incompatible(path, message))
      case None          => Right(LockedApplyProvenance(
          path,
          lockFile.tools.map(tool =>
            tool.name -> tool
          ).toMap
        ))

  private def firstProblem(
      prepared: PreparedPlan,
      lockFile: LockFile,
      metadataClient: BinaryMetadataClient
  ): Option[String] =
    val parsedVersions = parseLockedVersions(lockFile)
    schemaProblem(lockFile)
      .orElse(profileProblem(prepared, lockFile))
      .orElse(fingerprintProblem(prepared, lockFile))
      .orElse(duplicateToolProblem(lockFile))
      .orElse(lockedVersionTupleProblem(lockFile, parsedVersions))
      .orElse(toolProblem(prepared.plan.tools, lockFile, parsedVersions, metadataClient))

  private def parseLockedVersions(
      lockFile: LockFile
  ): Map[ToolName, Either[String, LockedVersion]] =
    lockFile.tools.map(tool => tool.name -> LockedVersion.parse(tool.name, tool)).toMap

  private def schemaProblem(lockFile: LockFile): Option[String] =
    Option.when(lockFile.schemaVersion != LockFile.schemaVersion)(
      s"expected schema version ${LockFile.schemaVersion}, found ${lockFile.schemaVersion}"
    )

  private def profileProblem(prepared: PreparedPlan, lockFile: LockFile): Option[String] =
    Option.when(lockFile.profileName != prepared.profileName)(
      s"expected profile '${prepared.profileName}', found '${lockFile.profileName}'"
    )

  private def fingerprintProblem(prepared: PreparedPlan, lockFile: LockFile): Option[String] =
    Option.when(lockFile.manifestFingerprint != prepared.manifestFingerprint)(
      s"manifest fingerprint changed: expected ${prepared.manifestFingerprint}, " +
        s"found ${lockFile.manifestFingerprint}; rerun `binstaller lock --config <file>`"
    )

  private def duplicateToolProblem(lockFile: LockFile): Option[String] =
    val duplicates = lockFile.tools.groupBy(_.name).collect:
      case (name, values) if values.size > 1 => name
    duplicates.toVector.map(_.value).sorted.headOption
      .map(name => s"duplicate lock entry for tool '$name'")

  private def lockedVersionTupleProblem(
      lockFile: LockFile,
      parsedVersions: Map[ToolName, Either[String, LockedVersion]]
  ): Option[String] = lockFile.tools.view
    .flatMap(tool => parsedVersions(tool.name).left.toOption)
    .headOption

  private def toolProblem(
      tools: Vector[ResolvedTool],
      lockFile: LockFile,
      parsedVersions: Map[ToolName, Either[String, LockedVersion]],
      metadataClient: BinaryMetadataClient
  ): Option[String] =
    val lockedTools = lockFile.tools.map(tool => tool.name -> tool).toMap
    tools.view.flatMap(tool =>
      lockedTools.get(tool.name) match
        case None             => Some(s"missing lock entry for tool '${tool.name}'")
        case Some(lockedTool) =>
          validateTool(tool, lockedTool, parsedVersions(tool.name), metadataClient)
    ).headOption

  private def validateTool(
      tool: ResolvedTool,
      lockedTool: LockFileTool,
      parsedVersion: Either[String, LockedVersion],
      metadataClient: BinaryMetadataClient
  ): Option[String] = parsedVersion match
    case Left(message)        => Some(message)
    case Right(lockedVersion) => incompleteProvenance(tool, lockedTool)
        .orElse(versionProblem(tool, lockedVersion))
        .orElse(checksumProblem(tool, lockedTool))
        .orElse(downloadUrlProblem(tool, lockedTool))
        .orElse(downloadMetadataProblem(tool, lockedTool, metadataClient))

  private def incompleteProvenance(
      tool: ResolvedTool,
      lockedTool: LockFileTool
  ): Option[String] = Option.when(
    lockedTool.downloadProvenance.initialUrl.trim.isEmpty ||
      lockedTool.downloadProvenance.finalUrl.trim.isEmpty
  )(s"tool '${tool.name}' has incomplete download provenance")
    .orElse:
      Option.when(
        lockedTool.checksum.isEmpty
      )(
        s"tool '${tool.name}' has no locked sha256 digest; regenerate the lock file"
      )

  private def versionProblem(tool: ResolvedTool, lockedVersion: LockedVersion): Option[String] =
    (tool.version, lockedVersion) match
      case (
            ResolvedVersion.Concrete(value, provenance),
            LockedVersion.Concrete(lockedValue, lockedProvenance)
          ) =>
        if lockedValue != value then
          Some(
            s"tool '${tool.name}' version changed: lock has '$lockedValue', resolved '$value'"
          )
        else provenanceProblem(tool, provenance, lockedProvenance)
      case (ResolvedVersion.DynamicLatestUrl(_), LockedVersion.Dynamic) => None
      case (ResolvedVersion.Concrete(value, _), LockedVersion.Dynamic)  =>
        Some(s"tool '${tool.name}' is missing locked resolved version '$value'")
      case (ResolvedVersion.DynamicLatestUrl(_), _: LockedVersion.Concrete) => Some(
          s"tool '${tool.name}' lock is not marked as a dynamic source"
        )

  private def provenanceProblem(
      tool: ResolvedTool,
      provenance: Option[UrlProvenance],
      lockedProvenance: Option[UrlProvenance]
  ): Option[String] = (provenance, lockedProvenance) match
    case (Some(current), Some(locked)) if current != locked =>
      Some(s"tool '${tool.name}' version provenance changed")
    case (Some(_), None) => Some(s"tool '${tool.name}' is missing locked version provenance")
    case (None, Some(_)) => Some(s"tool '${tool.name}' lock has unexpected version provenance")
    case _               => None

  // Checked for every tool, not only for tools without a manifest checksum as it once was: a
  // malformed locked digest is a corrupt lock file whichever way the manifest is written, and this
  // is what lets every later comparison work on parsed digests instead of raw strings.
  private def lockedChecksumFormatProblem(
      tool: ResolvedTool,
      lockedTool: LockFileTool
  ): Option[String] = lockedTool.checksum.filter: actual =>
    actual.algorithm != "sha256" || Sha256Digest.fromString(actual.value).isLeft
  .map(actual => s"tool '${tool.name}' has invalid locked checksum ${render(actual)}")

  private def checksumProblem(tool: ResolvedTool, lockedTool: LockFileTool): Option[String] =
    val current = tool.download.checksum.map(lockChecksum)
    lockedChecksumFormatProblem(tool, lockedTool).orElse:
      (current, lockedTool.checksum) match
        case (Some(expected), Some(actual))
            if expected.algorithm != actual.algorithm ||
              Sha256Digest.fromString(expected.value) != Sha256Digest.fromString(actual.value) =>
          Some(
            s"tool '${tool.name}' checksum changed: lock has ${render(actual)}, " +
              s"manifest has ${render(expected)}"
          )
        case (Some(expected), None) =>
          Some(s"tool '${tool.name}' is missing locked checksum ${render(expected)}")
        case _ => None

  private def downloadUrlProblem(tool: ResolvedTool, lockedTool: LockFileTool): Option[String] =
    Option.when(lockedTool.downloadProvenance.initialUrl != tool.download.url)(
      s"tool '${tool.name}' download URL changed: lock has " +
        s"'${lockedTool.downloadProvenance.initialUrl}', resolved '${tool.download.url}'"
    )

  private def downloadMetadataProblem(
      tool: ResolvedTool,
      lockedTool: LockFileTool,
      metadataClient: BinaryMetadataClient
  ): Option[String] = metadataClient.metadata(tool.download.url) match
    case Left(error) => Some(
        s"tool '${tool.name}' download metadata could not be verified for " +
          s"${tool.download.url}: ${error.message}"
      )
    case Right(metadata) => provenanceDriftProblem(tool, lockedTool, metadata)
        .orElse(sizeDriftProblem(tool, lockedTool, metadata))
        .orElse(digestDriftProblem(tool, lockedTool, metadata))

  private def provenanceDriftProblem(
      tool: ResolvedTool,
      lockedTool: LockFileTool,
      metadata: BinaryMetadata
  ): Option[String] = Option.when(metadata.provenance != lockedTool.downloadProvenance)(
    s"tool '${tool.name}' download provenance changed: lock final URL " +
      s"'${lockedTool.downloadProvenance.finalUrl}', current final URL " +
      s"'${metadata.provenance.finalUrl}'"
  )

  private def sizeDriftProblem(
      tool: ResolvedTool,
      lockedTool: LockFileTool,
      metadata: BinaryMetadata
  ): Option[String] = (lockedTool.sizeBytes, metadata.sizeBytes) match
    case (Some(expected), Some(actual)) if expected != actual =>
      Some(s"tool '${tool.name}' size changed: lock has $expected bytes, current is $actual bytes")
    case _ => None

  private def digestDriftProblem(
      tool: ResolvedTool,
      lockedTool: LockFileTool,
      metadata: BinaryMetadata
  ): Option[String] = (lockedTool.checksum, metadata.sha256) match
    case (Some(expected), Some(actual))
        if Sha256Digest.fromString(expected.value) != Right(actual) =>
      Some(
        s"tool '${tool.name}' sha256 changed: lock has ${expected.value}, " +
          s"current GET has ${actual.value}"
      )
    case (Some(_), None) => Some(s"tool '${tool.name}' metadata verification returned no sha256")
    case _               => None

  private def lockChecksum(checksum: ResolvedChecksum): LockFileChecksum =
    LockFileChecksum.fromResolved(checksum)

  private def render(checksum: LockFileChecksum): String =
    s"${checksum.algorithm} ${checksum.value}"
