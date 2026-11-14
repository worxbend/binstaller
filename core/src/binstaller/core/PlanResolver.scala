package binstaller.core

import binstaller.config.ArchiveSpec
import binstaller.config.BinaryDistributionProfile
import binstaller.config.BinaryToolSpec
import binstaller.config.ChecksumDiscoveryKind
import binstaller.config.ChecksumDiscoverySpec
import binstaller.config.ChecksumSpec
import binstaller.config.Diagnostics
import binstaller.config.DownloadSpec
import binstaller.config.DynamicVersionKind
import binstaller.config.ExtractMapping
import binstaller.config.PlanEntry
import binstaller.config.ToolName
import binstaller.config.ValidationError
import binstaller.config.VersionResolverKind
import binstaller.config.VersionSource

import java.nio.file.Path
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Resolver for converting typed manifests into executable plans. */
object PlanResolver:

  /** Resolve variables, versions, URLs, install directories, and selected archive mappings. */
  def resolve(
      profile: BinaryDistributionProfile,
      options: ResolutionOptions,
      httpTextClient: HttpTextClient
  ): Either[ResolvePlanError.ValidationFailed, ResolvedPlan] = validated(
    ResolutionBuilder(profile, options, httpTextClient)
      .resolve(ToolSelector.hostEntries(profile.spec.plan, options.hostPlatform), Vector.empty)
  )

  /**
   * Resolve only the tools `selection` keeps.
   *
   * Selection runs first, on the manifest, so a deselected tool is never resolved: no request is
   * made for it, and its resolution errors cannot fail the run. Its installDir is still placed from
   * the manifest alone, because a selected tool installed over it would replace it.
   */
  private[core] def resolve(
      profile: BinaryDistributionProfile,
      options: ResolutionOptions,
      httpTextClient: HttpTextClient,
      selection: ToolSelection
  ): Either[ResolvePlanError, ResolvedPlan] =
    ToolSelector.select(profile.spec.plan, options.hostPlatform, selection).flatMap: selected =>
      val selectedIndexes = selected.map(_._2).toSet
      val bystanders      = ToolSelector.hostEntries(profile.spec.plan, options.hostPlatform)
        .filterNot((_, index) => selectedIndexes(index))
      validated(ResolutionBuilder(profile, options, httpTextClient).resolve(selected, bystanders))

  private def validated(
      resolved: ResolvedValue[ResolvedPlan]
  ): Either[ResolvePlanError.ValidationFailed, ResolvedPlan] =
    if resolved.errors.isEmpty then Right(resolved.value)
    else Left(ResolvePlanError.ValidationFailed(resolved.errors))

private[core] final case class ResolvedValue[+A](value: A, errors: Vector[ValidationError]):
  def map[B](f: A => B): ResolvedValue[B] = ResolvedValue(f(value), errors)

private[core] object ResolvedValue:
  def valid[A](value: A): ResolvedValue[A] = ResolvedValue(value, Vector.empty)

  def invalid[A](value: A, path: String, message: String): ResolvedValue[A] =
    ResolvedValue(value, Vector(ValidationError(path, message)))

  /**
   * Combine per-element results, keeping every value and concatenating all errors.
   *
   * Resolution never stops at the first bad field: a user fixing a manifest wants the whole list of
   * problems at once, so every element contributes both its value and its errors.
   */
  def sequence[A](values: Vector[ResolvedValue[A]]): ResolvedValue[Vector[A]] =
    ResolvedValue(values.map(_.value), values.flatMap(_.errors))

  /** Combine per-key results into a map, concatenating all errors. */
  def sequenceMap[A](
      values: Vector[(String, ResolvedValue[A])]
  ): ResolvedValue[Map[String, A]] = ResolvedValue(
    values.map((name, value) => name -> value.value).toMap,
    values.flatMap((_, value) => value.errors)
  )

private[core] final class ResolutionBuilder(
    profile: BinaryDistributionProfile,
    options: ResolutionOptions,
    httpTextClient: HttpTextClient
):

  /**
   * Resolve `activeEntries`: the plan entries taking part in this run, with manifest indexes.
   *
   * `bystanders` are the host entries left out by `--only`/`--skip`. They are never resolved; only
   * their install directories are compared against the active ones.
   */
  def resolve(
      activeEntries: Vector[(PlanEntry, Int)],
      bystanders: Vector[(PlanEntry, Int)]
  ): ResolvedValue[ResolvedPlan] =
    val manifestVars = resolveManifestVars()
    val policy       = resolvePolicy(options.runtimeVariables ++ manifestVars.value)
    val baseVars     = options.runtimeVariables ++ manifestVars.value +
      ("appsDir" -> policy.value.appsDir)
    val activeVersionRefs = activeEntries.map(_._1.spec.versionRef).toSet
    val versionResults    = resolveVersions(baseVars, activeVersionRefs)
    val versions          = ResolvedValue.sequenceMap(versionResults)
    val tools             =
      resolveTools(activeEntries, baseVars, versions.value ++ standInVersions(versionResults))
    // Errors must name the manifest position, and a host-excluded or deselected entry shifts the
    // position in `tools`. `sequence` keeps one tool per entry, so the zip stays aligned.
    val indexedTools           = tools.value.zip(activeEntries.map(_._2))
    val installDirectoryErrors = validateInstallDirectories(policy.value, indexedTools) ++
      bystanderOverlapErrors(indexedTools, bystanderInstallDirs(bystanders, baseVars))
    val strictPolicyErrors = StrictPolicyValidator.validate(
      profile,
      policy.value,
      indexedTools,
      activeVersionRefs
    )

    ResolvedValue(
      ResolvedPlan(policy.value, tools.value, options.redactions),
      manifestVars.errors ++ policy.errors ++ versions.errors ++ tools.errors
        ++ installDirectoryErrors ++ strictPolicyErrors
    )

  // A version source that failed is reported once, at the source. The tools built on it are still
  // checked for their own mistakes, against a stand-in, so that no field repeats the failure as an
  // unresolved ${version}. The stand-in cannot reach an install: the source's error fails the run
  // and the plan value is discarded.
  private def standInVersions(
      versionResults: Vector[(String, ResolvedValue[ResolvedVersion])]
  ): Map[String, ResolvedVersion] = versionResults.collect:
    case (name, version) if version.errors.nonEmpty =>
      name -> ResolvedVersion.Concrete(unresolvedVersionStandIn, None)
  .toMap

  private val unresolvedVersionStandIn: String = "unresolved"

  private def resolveManifestVars(): ResolvedValue[Map[String, String]] =
    val rawVars  = profile.spec.vars
    val resolved = rawVars.toVector.map:
      case (name, value) =>
        val path = s"spec.vars.$name"
        name -> interpolate(value, path, options.runtimeVariables ++ rawVars)
    ResolvedValue.sequenceMap(resolved)

  private def resolvePolicy(vars: Map[String, String]): ResolvedValue[ResolvedPolicy] =
    val appsDir   = interpolate(profile.spec.policy.appsDir, "spec.policy.appsDir", vars)
    val stateFile = profile.spec.policy.stateFile match
      case Some(value) => interpolate(value, "spec.policy.stateFile", vars).map(Some(_))
      case None        => ResolvedValue.valid(None)
    val stateFilePathErrors = stateFile.value.toVector.flatMap: value =>
      ResolvedPathValidator.stateFile(value, "spec.policy.stateFile")

    ResolvedValue(
      ResolvedPolicy.fromManifest(appsDir.value, stateFile.value, profile.spec.policy),
      appsDir.errors ++ stateFile.errors ++ stateFilePathErrors
    )

  private def resolveVersions(
      vars: Map[String, String],
      activeVersionRefs: Set[String]
  ): Vector[(String, ResolvedValue[ResolvedVersion])] =
    profile.spec.versions.toVector.filter((name, _) => activeVersionRefs(name)).map:
      case (name, source) => name -> resolveVersionSource(name, source, vars)

  private def resolveVersionSource(
      name: String,
      source: VersionSource,
      vars: Map[String, String]
  ): ResolvedValue[ResolvedVersion] = source match
    case VersionSource.Pinned(value) =>
      val path     = s"spec.versions.$name"
      val resolved = interpolate(value, path, vars)
      val errors   = resolved.errors ++ concreteVersionErrors(resolved.value, path, name)
      ResolvedValue(ResolvedVersion.Concrete(resolved.value, None), errors)
    case VersionSource.Dynamic(DynamicVersionKind.LatestUrl, note) =>
      ResolvedValue.valid(ResolvedVersion.DynamicLatestUrl(note))
    case VersionSource.Resolver(VersionResolverKind.HttpText, url) =>
      resolveHttpTextVersion(name, url, vars)

  private def resolveHttpTextVersion(
      name: String,
      url: String,
      vars: Map[String, String]
  ): ResolvedValue[ResolvedVersion] =
    val path        = s"spec.versions.$name.resolver.url"
    val resolvedUrl = interpolate(url, path, vars)
    // The https rule only applies to a cleanly interpolated URL; validating the raw template
    // would report a second, spurious error for the same field.
    val urlErrors =
      if resolvedUrl.errors.nonEmpty then Vector.empty
      else httpsUrlErrors(resolvedUrl.value, path)
    val fetched =
      if resolvedUrl.errors.nonEmpty || urlErrors.nonEmpty then
        ResolvedValue.valid(HttpTextResponse("", UrlProvenance.direct(resolvedUrl.value)))
      else
        httpTextClient.getTextWithProvenance(resolvedUrl.value) match
          case Right(response) => ResolvedValue.valid(response.copy(text = response.text.trim))
          case Left(error)     => ResolvedValue.invalid(
              HttpTextResponse(
                "",
                error.provenance.getOrElse(UrlProvenance.direct(resolvedUrl.value))
              ),
              path,
              s"http-text resolver failed: ${error.message}"
            )

    val requestErrors = resolvedUrl.errors ++ urlErrors ++ fetched.errors
    // An empty answer is its own error only when the request worked; after a failed request it
    // would restate that failure.
    val valueErrors =
      if requestErrors.nonEmpty then Vector.empty
      else concreteVersionErrors(fetched.value.text, path, name)

    ResolvedValue(
      ResolvedVersion.Concrete(fetched.value.text, Some(fetched.value.provenance)),
      requestErrors ++ valueErrors
    )

  private def concreteVersionErrors(
      value: String,
      path: String,
      name: String
  ): Vector[ValidationError] =
    if value.isEmpty then
      Vector(ValidationError(path, s"version '$name' did not resolve to a concrete value"))
    // A version is printed in the plan and spliced into paths and URLs. One that carries a line
    // break or an escape sequence, as a hostile version endpoint can return, forges plan lines.
    else if value.exists(Character.isISOControl) then
      Vector(ValidationError(path, s"version '$name' resolved to a value with control characters"))
    else Vector.empty

  private def resolveTools(
      entries: Vector[(PlanEntry, Int)],
      baseVars: Map[String, String],
      versions: Map[String, ResolvedVersion]
  ): ResolvedValue[Vector[ResolvedTool]] =
    val resolved = entries.map:
      case (entry, index) => resolveTool(entry, index, baseVars, versions)
    ResolvedValue.sequence(resolved)

  private def resolveTool(
      entry: PlanEntry,
      index: Int,
      baseVars: Map[String, String],
      versions: Map[String, ResolvedVersion]
  ): ResolvedValue[ResolvedTool] =
    val spec              = entry.spec
    val version           = versions.getOrElse(spec.versionRef, ResolvedVersion.Concrete(""))
    val versionVars       = concreteVersionVars(version)
    val vars              = baseVars ++ versionVars + ("tool" -> entry.name.value)
    val specPath          = s"spec.plan[$index].spec"
    val installDir        = resolveTemplate(spec.installDir, s"$specPath.installDir", vars, version)
    val localVars         = vars + ("installDir"              -> installDir.value)
    val download          = resolveDownload(spec.download, specPath, localVars, version)
    val createDirectories = resolveStringVector(
      spec.createDirectories,
      s"$specPath.createDirectories",
      localVars,
      version
    )
    val executables = resolveExecutables(spec, specPath, localVars, version)
    val symlinks    = resolveSymlinks(spec, specPath, localVars, version, installDir.value)

    ResolvedValue(
      ResolvedTool(
        name = entry.name,
        description = entry.description,
        version = version,
        installDir = installDir.value,
        createDirectories = createDirectories.value,
        download = download.value,
        executables = executables.value,
        symlinks = symlinks.value
      ),
      installDir.errors ++ createDirectories.errors ++ download.errors ++ executables.errors ++
        symlinks.errors ++ directBinaryExecutableErrors(spec, specPath)
    )

  // A direct-binary tool (no archive) stages the single downloaded file at exactly one executable
  // path, so any other count can never install: catch it at plan time with clear guidance instead
  // of the misleading "missing executable" failure the install pass would otherwise emit.
  private def directBinaryExecutableErrors(
      spec: binstaller.config.BinaryToolSpec,
      specPath: String
  ): Vector[ValidationError] =
    if spec.download.archive.isDefined || spec.executables.size == 1 then Vector.empty
    else
      Vector(ValidationError(
        s"$specPath.executables",
        s"a direct binary tool without an archive must declare exactly one executable " +
          s"(got ${spec.executables.size}); use symlinks to expose it under additional names"
      ))

  private def concreteVersionVars(version: ResolvedVersion): Map[String, String] = version match
    case ResolvedVersion.Concrete(value, _) if value.nonEmpty => Map("version" -> value)
    case ResolvedVersion.Concrete(_, _) | ResolvedVersion.DynamicLatestUrl(_) => Map.empty

  private def resolveDownload(
      download: DownloadSpec,
      specPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[ResolvedDownload] =
    val path     = s"$specPath.download"
    val url      = resolveTemplate(download.url, s"$path.url", vars, version, httpsUrlErrors)
    val filename = resolveTemplate(
      download.filename,
      s"$path.filename",
      vars,
      version,
      ResolvedPathValidator.downloadFilename
    )
    val archive  = resolveArchive(download.archive, path, vars, version)
    val checksum = resolveChecksum(download.checksum, path, vars, version, filename.value)

    ResolvedValue(
      ResolvedDownload(url.value, filename.value, checksum.value, archive.value),
      url.errors ++ filename.errors ++ checksum.errors ++ archive.errors
    )

  private def resolveChecksum(
      checksum: Option[ChecksumSpec],
      downloadPath: String,
      vars: Map[String, String],
      version: ResolvedVersion,
      resolvedFilename: String
  ): ResolvedValue[Option[ResolvedChecksum]] = checksum match
    case None        => ResolvedValue.valid(None)
    case Some(value) => value.value match
        case Some(literal) => ResolvedValue.valid(Some(ResolvedChecksum(
            value.algorithm,
            literal,
            ResolvedChecksumSource.Configured
          )))
        case None => value.discover match
            case Some(discovery) => resolveDiscoveredChecksum(
                value,
                discovery,
                downloadPath,
                vars,
                version,
                resolvedFilename
              )
            case None => ResolvedValue.valid(None)

  private def resolveDiscoveredChecksum(
      checksum: ChecksumSpec,
      discovery: ChecksumDiscoverySpec,
      downloadPath: String,
      vars: Map[String, String],
      version: ResolvedVersion,
      resolvedFilename: String
  ): ResolvedValue[Option[ResolvedChecksum]] =
    val sourcePath = s"$downloadPath.checksum.discover"
    val url        = interpolate(discovery.url, s"$sourcePath.url", vars)
    val file       = discovery.file match
      case Some(value) => interpolate(value, s"$sourcePath.file", vars)
      case None        => ResolvedValue.valid(resolvedFilename)
    val fileErrors =
      if file.value.trim.nonEmpty then Vector.empty
      else Vector(ValidationError(s"$sourcePath.file", "checksum discovery file must not be empty"))
    val requestErrors = url.errors ++
      versionTemplateErrors(discovery.url, s"$sourcePath.url", version) ++
      httpsUrlErrors(url.value, s"$sourcePath.url") ++
      file.errors ++
      discovery.file.toVector.flatMap(value =>
        versionTemplateErrors(value, s"$sourcePath.file", version)
      ) ++ fileErrors

    if requestErrors.nonEmpty then ResolvedValue(None, requestErrors)
    else
      val fetched = httpTextClient.getTextWithProvenance(url.value) match
        case Right(response) => parseChecksumFile(
            response,
            checksum,
            discovery,
            file.value,
            sourcePath
          )
        case Left(error) => ResolvedValue.invalid(
            None,
            s"$sourcePath.url",
            s"checksum discovery failed: ${error.message}"
          )
      ResolvedValue(fetched.value, requestErrors ++ fetched.errors)

  private def parseChecksumFile(
      response: HttpTextResponse,
      checksum: ChecksumSpec,
      discovery: ChecksumDiscoverySpec,
      file: String,
      sourcePath: String
  ): ResolvedValue[Option[ResolvedChecksum]] = discovery.kind match
    case ChecksumDiscoveryKind.Sha256Sum => Sha256SumChecksumFile.find(response.text, file) match
        case Sha256SumChecksumFile.Lookup.Found(value) => ResolvedValue.valid(Some(ResolvedChecksum(
            checksum.algorithm,
            value,
            ResolvedChecksumSource.Discovered(
              response.provenance.initialUrl,
              file,
              response.provenance
            )
          )))
        case Sha256SumChecksumFile.Lookup.NotFound => ResolvedValue.invalid(
            None,
            sourcePath,
            s"checksum discovery missing sha256sum entry for '$file'"
          )
        case Sha256SumChecksumFile.Lookup.Ambiguous(paths) => ResolvedValue.invalid(
            None,
            sourcePath,
            s"checksum discovery found multiple sha256sum entries matching '$file' " +
              s"(${paths.mkString(", ")}); set checksum.discover.file to the exact path"
          )

  private def resolveArchive(
      archive: Option[ArchiveSpec],
      downloadPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Option[ResolvedArchive]] = archive match
    case None        => ResolvedValue.valid(None)
    case Some(value) =>
      val files = resolveExtractMappings(
        value.extract.files,
        s"$downloadPath.archive.extract.files",
        vars,
        version,
        ResolvedPathValidator.installRelativePath
      )
      val directories = resolveExtractMappings(
        value.extract.directories,
        s"$downloadPath.archive.extract.directories",
        vars,
        version,
        ResolvedPathValidator.archivePath
      )
      ResolvedValue(
        Some(ResolvedArchive(value, files.value, directories.value)),
        files.errors ++ directories.errors
      )

  // A file mapping names a file on both sides. Only a directory mapping may name the current
  // directory: the archive root as its source, the install directory as its target.
  private def resolveExtractMappings(
      mappings: Vector[ExtractMapping],
      path: String,
      vars: Map[String, String],
      version: ResolvedVersion,
      validatePath: (String, String, String) => Vector[ValidationError]
  ): ResolvedValue[Vector[ResolvedExtractMapping]] =
    val resolved = mappings.zipWithIndex.map:
      case (mapping, index) =>
        val fromPath = s"$path[$index].from"
        val toPath   = s"$path[$index].to"
        val from     = resolveTemplate(
          mapping.from,
          fromPath,
          vars,
          version,
          validatePath(_, _, "archive source")
        )
        val to = resolveTemplate(
          mapping.to,
          toPath,
          vars,
          version,
          validatePath(_, _, "archive target")
        )
        ResolvedValue(
          ResolvedExtractMapping(from.value, to.value),
          from.errors ++ to.errors
        )
    ResolvedValue.sequence(resolved)

  private def resolveExecutables(
      spec: BinaryToolSpec,
      specPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Vector[ResolvedExecutable]] =
    val resolved = spec.executables.zipWithIndex.map:
      case (executable, index) =>
        val path  = s"$specPath.executables[$index].path"
        val value = resolveTemplate(
          executable.path,
          path,
          vars,
          version,
          ResolvedPathValidator.installRelativePath(_, _, "executable path")
        )
        ResolvedValue(ResolvedExecutable(value.value, executable.mode), value.errors)
    ResolvedValue.sequence(resolved)

  private def resolveSymlinks(
      spec: BinaryToolSpec,
      specPath: String,
      vars: Map[String, String],
      version: ResolvedVersion,
      installDir: String
  ): ResolvedValue[Vector[ResolvedSymlink]] =
    val resolved = spec.symlinks.zipWithIndex.map:
      case (symlink, index) =>
        val pathPath   = s"$specPath.symlinks[$index].path"
        val targetPath = s"$specPath.symlinks[$index].target"
        val path       = resolveTemplate(
          symlink.path,
          pathPath,
          vars,
          version,
          ResolvedPathValidator.symlinkPath(_, _, installDir, symlink.privilege)
        )
        val target = resolveTemplate(
          symlink.target,
          targetPath,
          vars,
          version,
          ResolvedPathValidator.symlinkTarget(_, _, installDir)
        )
        val resolved = ResolvedSymlink(path.value, target.value, symlink.privilege)
        // Comparing the ends means anything only once each end resolved on its own.
        val endErrors =
          if path.errors.nonEmpty || target.errors.nonEmpty then Vector.empty
          else ResolvedPathValidator.distinctSymlinkEnds(resolved, pathPath, installDir)
        ResolvedValue(resolved, path.errors ++ target.errors ++ endErrors)
    ResolvedValue.sequence(resolved)

  private def resolveStringVector(
      values: Vector[String],
      path: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Vector[String]] =
    val resolved = values.zipWithIndex.map:
      case (value, index) =>
        val itemPath = s"$path[$index]"
        resolveTemplate(
          value,
          itemPath,
          vars,
          version,
          ResolvedPathValidator.installRelativePath(_, _, "create directory path")
        )
    ResolvedValue.sequence(resolved)

  private def versionTemplateErrors(
      rawTemplate: String,
      path: String,
      version: ResolvedVersion
  ): Vector[ValidationError] = version match
    case ResolvedVersion.Concrete(resolved, _) if resolved.nonEmpty           => Vector.empty
    case ResolvedVersion.Concrete(_, _) | ResolvedVersion.DynamicLatestUrl(_) =>
      TemplateInterpolator.variableNames(rawTemplate).collect:
        case "version" => ValidationError(
            path,
            "template references ${version}, but no concrete version is available"
          )

  private def interpolate(
      value: String,
      path: String,
      vars: Map[String, String]
  ): ResolvedValue[String] = TemplateInterpolator.interpolate(value, path, vars)

  /**
   * Resolve one templated manifest field.
   *
   * Every templated field goes through the same three steps in the same order, and the order
   * matters: interpolate the raw value, then complain if the RAW value referenced `$${version}`
   * while no concrete version exists, then validate the RESOLVED value as a path or URL. Checking
   * the wrong one of the two — raw where resolved is meant, or the reverse — is the mistake this
   * helper exists to make unrepeatable, since each call site previously spelled all three out.
   */
  private def resolveTemplate(
      raw: String,
      path: String,
      vars: Map[String, String],
      version: ResolvedVersion,
      validate: (String, String) => Vector[ValidationError]
  ): ResolvedValue[String] =
    val interpolated = interpolate(raw, path, vars)
    ResolvedValue(
      interpolated.value,
      interpolated.errors ++ versionTemplateErrors(raw, path, version) ++
        validate(interpolated.value, path)
    )

  /** Resolve a templated field that carries no path or URL rule of its own. */
  private def resolveTemplate(
      raw: String,
      path: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[String] = resolveTemplate(raw, path, vars, version, (_, _) => Vector.empty)

  private def normalizedBarePath(value: String): Option[Path] =
    Try(Path.of(value).toAbsolutePath.normalize()).toOption

  /**
   * Normalize a manifest path, or report why it is not a usable path.
   *
   * Both steps have to happen before any two paths can be compared: `pathSyntax` rejects the values
   * that must never reach the filesystem at all, and normalization resolves `.`, `..` and relative
   * segments so that a containment check on the result means what it says.
   */
  private def normalizedPath(
      value: String,
      path: String,
      label: String
  ): Either[Vector[ValidationError], Path] =
    val syntaxErrors = ResolvedPathValidator.pathSyntax(value, path, label)
    if syntaxErrors.nonEmpty then Left(syntaxErrors)
    // No shell expands `~` here: it would become a directory literally named `~` under the cwd.
    else if value == "~" || value.startsWith("~/") then
      Left(Vector(ValidationError(path, s"$label must not start with '~'; use $${HOME}")))
    else
      Try(Path.of(value).toAbsolutePath.normalize()) match
        case Failure(error) =>
          Left(Vector(ValidationError(path, s"invalid $label: ${Diagnostics.describe(error)}")))
        case Success(result) => Right(result)

  /**
   * The rule: an install root must live strictly inside appsDir.
   *
   * Equal to appsDir is rejected as well as outside it, because a tool whose installDir *is* the
   * apps root would have the replace step delete every other tool on the next apply.
   */
  private def containmentErrors(
      tool: ResolvedTool,
      index: Int,
      appsDir: Path
  ): Vector[ValidationError] =
    val path = s"spec.plan[$index].spec.installDir"
    normalizedPath(tool.installDir, path, "installDir") match
      case Left(errors)                               => errors
      case Right(installDir) if installDir == appsDir =>
        Vector(ValidationError(path, "installDir must be a child of appsDir, not appsDir itself"))
      case Right(installDir) if !installDir.startsWith(appsDir) =>
        Vector(ValidationError(path, "installDir must resolve inside spec.policy.appsDir"))
      case Right(_) => Vector.empty

  private def validateInstallDirectories(
      policy: ResolvedPolicy,
      indexedTools: Vector[(ResolvedTool, Int)]
  ): Vector[ValidationError] =
    normalizedPath(policy.appsDir, "spec.policy.appsDir", "appsDir") match
      case Left(errors)   => errors
      case Right(appsDir) =>
        indexedTools.flatMap((tool, index) => containmentErrors(tool, index, appsDir)) ++
          nestedInstallDirectoryErrors(indexedTools)

  private def nestedInstallDirectoryErrors(
      indexedTools: Vector[(ResolvedTool, Int)]
  ): Vector[ValidationError] =
    val indexed = indexedTools.flatMap:
      // Syntax failures are already reported by containmentErrors; only comparable paths matter.
      case (tool, index) => normalizedBarePath(tool.installDir).map: path =>
          (tool, index, path)

    indexed.flatMap:
      case (tool, index, installDir) => indexed.collect:
          case (otherTool, _, otherInstallDir)
              if tool.name != otherTool.name && installDir.startsWith(otherInstallDir) =>
            ValidationError(
              s"spec.plan[$index].spec.installDir",
              s"installDir must not be nested inside tool '${otherTool.name}' installDir"
            )

  // Placed from the manifest alone, without a request: a pinned version stands as written, any
  // other source takes the stand-in. A bystander whose installDir does not come out as a clean path
  // is left out, because it is not part of this run and must not fail it.
  private def bystanderInstallDirs(
      bystanders: Vector[(PlanEntry, Int)],
      baseVars: Map[String, String]
  ): Vector[(ToolName, Path)] = bystanders.flatMap: (entry, index) =>
    val path = s"spec.plan[$index].spec.installDir"
    val vars = baseVars + ("version" -> offlineVersion(entry.spec.versionRef, baseVars)) +
      ("tool" -> entry.name.value)
    val installDir = interpolate(entry.spec.installDir, path, vars)
    if installDir.errors.nonEmpty then None
    else normalizedPath(installDir.value, path, "installDir").toOption.map(entry.name -> _)

  private def offlineVersion(versionRef: String, vars: Map[String, String]): String =
    profile.spec.versions.get(versionRef)
      .collect:
        case source @ VersionSource.Pinned(_) => resolveVersionSource(versionRef, source, vars)
      .collect:
        case ResolvedValue(ResolvedVersion.Concrete(value, _), errors) if errors.isEmpty => value
      .getOrElse(unresolvedVersionStandIn)

  // Only a selected tool can be installed by this run, so only a pair with one in it is reported,
  // and at the selected tool's own path. Both directions destroy an install: the selected tool's
  // replace step swaps out the whole directory it names, whatever lives inside it.
  private def bystanderOverlapErrors(
      indexedTools: Vector[(ResolvedTool, Int)],
      bystanders: Vector[(ToolName, Path)]
  ): Vector[ValidationError] =
    for
      (tool, index)           <- indexedTools
      installDir              <- normalizedBarePath(tool.installDir).toVector
      (name, otherInstallDir) <- bystanders
      message                 <- overlapMessage(installDir, name, otherInstallDir)
    yield ValidationError(s"spec.plan[$index].spec.installDir", message)

  private def overlapMessage(
      installDir: Path,
      bystander: ToolName,
      bystanderInstallDir: Path
  ): Option[String] =
    if installDir.startsWith(bystanderInstallDir) then
      Some(s"installDir must not be nested inside tool '${bystander.value}' installDir")
    else if bystanderInstallDir.startsWith(installDir) then
      Some(s"installDir must not contain the installDir of tool '${bystander.value}'")
    else None

  private def httpsUrlErrors(value: String, path: String): Vector[ValidationError] =
    RuntimeUrl.httpsUri(value) match
      case Right(_)      => Vector.empty
      case Left(message) => Vector(ValidationError(path, message))
