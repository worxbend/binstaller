package binstaller.config

import binstaller.config.YamlDecode.*

private[config] object ManifestDecoder:

  def decode(value: Any): DecodeResult[BinaryDistributionProfile] =
    DecodeResult.accumulate: acc =>
      val root       = acc(asMap(value, "$"))
      acc.report(unknownKeyErrors(root, "$", Set("apiVersion", "kind", "metadata", "spec")))
      val apiVersion = acc(enumValue(
        requiredString(root, "apiVersion"),
        "apiVersion",
        ApiVersion.values.toVector,
        ApiVersion.V1Alpha1,
        _.value
      ))
      val kind = acc(enumValue(
        requiredString(root, "kind"),
        "kind",
        ManifestKind.values.toVector,
        ManifestKind.BinaryDistributionProfile,
        _.value
      ))
      val metadata = acc(decodeMetadata(requiredMap(root, "metadata")))
      val spec     = acc(decodeSpec(requiredMap(root, "spec")))
      BinaryDistributionProfile(
        apiVersion = apiVersion,
        kind = kind,
        metadata = metadata,
        spec = spec
      )

  private def decodeMetadata(input: DecodeResult[YamlMap]): DecodeResult[ManifestMetadata] =
    DecodeResult.accumulate: acc =>
      val map = acc(input)
      acc.report(unknownKeyErrors(map, "metadata", Set("name", "labels", "annotations")))
      val name        = acc(requiredString(map, "metadata.name"))
      val labels      = acc(optionalStringMap(map, "labels", "metadata.labels"))
      val annotations = acc(optionalStringMap(map, "annotations", "metadata.annotations"))
      ManifestMetadata(name, labels, annotations)

  private def decodeSpec(input: DecodeResult[YamlMap]): DecodeResult[ProfileSpec] =
    DecodeResult.accumulate: acc =>
      val map = acc(input)
      acc.report(unknownKeyErrors(map, "spec", Set("policy", "vars", "versions", "plan")))
      val policy   = acc(decodePolicy(requiredMap(map, "spec.policy")))
      val vars     = acc(optionalStringMap(map, "vars", "spec.vars"))
      val versions = acc(decodeVersions(requiredMap(map, "spec.versions")))
      val plan     = acc(decodePlan(requiredList(map, "spec.plan")))
      ProfileSpec(policy, vars, versions, plan)

  private def decodePolicy(input: DecodeResult[YamlMap]): DecodeResult[InstallPolicy] =
    DecodeResult.accumulate: acc =>
      val map = acc(input)
      acc.report(unknownKeyErrors(
        map,
        "spec.policy",
        Set(
          "mode",
          "continueOnError",
          "appsDir",
          "allowSudoSymlinks",
          "allowDynamicLatestUrls",
          "allowMissingChecksums",
          "stateFile"
        )
      ))
      val mode = acc(optionalEnumValue(
        optionalString(map, "mode", "spec.policy.mode"),
        "spec.policy.mode",
        PolicyMode.values.toVector,
        PolicyMode.Developer,
        _.value
      ))
      val continueOnError = acc(
        optionalBoolean(map, "continueOnError", "spec.policy.continueOnError", default = false)
          .map(PolicyOverride.fromBoolean)
      )
      val appsDir           = acc(requiredString(map, "spec.policy.appsDir"))
      val allowSudoSymlinks = acc(optionalBoolean(
        map,
        "allowSudoSymlinks",
        "spec.policy.allowSudoSymlinks",
        default = false
      ).map(PolicyOverride.fromBoolean))
      val allowDynamicLatestUrls = acc(optionalPolicyOverride(
        map,
        "allowDynamicLatestUrls",
        "spec.policy.allowDynamicLatestUrls"
      ))
      val allowMissingChecksums = acc(optionalPolicyOverride(
        map,
        "allowMissingChecksums",
        "spec.policy.allowMissingChecksums"
      ))
      val stateFile = acc(optionalString(map, "stateFile", "spec.policy.stateFile"))
      InstallPolicy(
        mode = mode,
        continueOnError = continueOnError,
        appsDir = appsDir,
        allowSudoSymlinks = allowSudoSymlinks,
        allowDynamicLatestUrls = allowDynamicLatestUrls,
        allowMissingChecksums = allowMissingChecksums,
        stateFile = stateFile
      )

  private def decodeVersions(input: DecodeResult[YamlMap])
      : DecodeResult[Map[String, VersionSource]] =
    DecodeResult.accumulate: acc =>
      val map     = acc(input)
      val entries = map.toVector.map:
        case (name, source) => name -> acc(decodeVersionSource(source, s"spec.versions.$name"))
      entries.toMap

  private def decodeVersionSource(value: Any, path: String): DecodeResult[VersionSource] =
    value match
      case scalar: String => DecodeResult.valid(VersionSource.Pinned(scalar))
      // YAML parses `1.2`/`3`/`true` as Double/Int/Boolean, silently dropping trailing zeros
      // (1.20 -> 1.2). Reject with a targeted message instead of the catch-all shape error so
      // authors are told to quote the version rather than being misled about the block shape.
      case (_: Number) | (_: Boolean) => DecodeResult.invalid(
          VersionSource.Pinned(""),
          path,
          "version must be a quoted string; wrap numeric or boolean-looking versions in quotes " +
            "(e.g. \"1.20\")"
        )
      case map: YamlMap @unchecked if map.contains("dynamic") =>
        DecodeResult.accumulate: acc =>
          acc.report(unknownKeyErrors(map, path, Set("dynamic")))
          acc(decodeDynamicVersion(requiredMap(map, s"$path.dynamic"), s"$path.dynamic"))
      case map: YamlMap @unchecked if map.contains("resolver") =>
        DecodeResult.accumulate: acc =>
          acc.report(unknownKeyErrors(map, path, Set("resolver")))
          acc(decodeVersionResolver(requiredMap(map, s"$path.resolver"), s"$path.resolver"))
      case _ => DecodeResult.invalid(
          VersionSource.Pinned(""),
          path,
          "version source must be a pinned string, dynamic block, or resolver block"
        )

  private def decodeDynamicVersion(
      input: DecodeResult[YamlMap],
      path: String
  ): DecodeResult[VersionSource] =
    DecodeResult.accumulate: acc =>
      val map = acc(input)
      acc.report(unknownKeyErrors(map, path, Set("type", "note")))
      val kind = acc(enumValue(
        requiredString(map, "type", s"$path.type"),
        s"$path.type",
        DynamicVersionKind.values.toVector,
        DynamicVersionKind.LatestUrl,
        _.value
      ))
      val note = acc(optionalString(map, "note", s"$path.note"))
      VersionSource.Dynamic(kind, note)

  private def decodeVersionResolver(
      input: DecodeResult[YamlMap],
      path: String
  ): DecodeResult[VersionSource] =
    DecodeResult.accumulate: acc =>
      val map = acc(input)
      acc.report(unknownKeyErrors(map, path, Set("type", "url")))
      val kind = acc(enumValue(
        requiredString(map, "type", s"$path.type"),
        s"$path.type",
        VersionResolverKind.values.toVector,
        VersionResolverKind.HttpText,
        _.value
      ))
      val url = acc(requiredString(map, s"$path.url"))
      VersionSource.Resolver(kind, url)

  private def decodePlan(input: DecodeResult[Vector[Any]]): DecodeResult[Vector[PlanEntry]] =
    DecodeResult.accumulate: acc =>
      val list = acc(input)
      list.zipWithIndex.map:
        case (value, index) => acc(decodePlanEntry(asMap(value, s"spec.plan[$index]"), index))

  private def decodePlanEntry(input: DecodeResult[YamlMap], index: Int): DecodeResult[PlanEntry] =
    val path = s"spec.plan[$index]"
    DecodeResult.accumulate: acc =>
      val map = acc(input)
      acc.report(unknownKeyErrors(map, path, Set("name", "kind", "description", "when", "spec")))
      val name = acc(requiredString(map, s"$path.name"))
      val kind = acc(enumValue(
        requiredString(map, s"$path.kind"),
        s"$path.kind",
        PlanKind.values.toVector,
        PlanKind.BinaryTool,
        _.value
      ))
      val description = acc(optionalString(map, "description", s"$path.description"))
      val when        = acc(optionalWhen(map, s"$path.when"))
      val spec        = acc(decodeBinaryToolSpec(requiredMap(map, s"$path.spec"), path))
      PlanEntry(
        name = name,
        kind = kind,
        description = description,
        when = when,
        spec = spec
      )

  private def optionalWhen(map: YamlMap, path: String): DecodeResult[Option[WhenClause]] =
    optionalBlock(map, "when", path, Set("os", "architecture")): (whenMap, acc) =>
      val os           = acc(optionalOs(whenMap, s"$path.os"))
      val architecture = acc(optionalString(whenMap, "architecture", s"$path.architecture"))
      WhenClause(os, architecture)

  private def optionalOs(map: YamlMap, path: String): DecodeResult[Option[OsClause]] =
    optionalBlock(map, "os", path, Set("family")): (osMap, acc) =>
      OsClause(acc(optionalString(osMap, "family", s"$path.family")))

  private def decodeBinaryToolSpec(
      input: DecodeResult[YamlMap],
      entryPath: String
  ): DecodeResult[BinaryToolSpec] =
    val path = s"$entryPath.spec"
    DecodeResult.accumulate: acc =>
      val map = acc(input)
      acc.report(unknownKeyErrors(
        map,
        path,
        Set(
          "versionRef",
          "installDir",
          "createDirectories",
          "download",
          "installer",
          "executables",
          "symlinks"
        )
      ))
      val versionRef        = acc(requiredString(map, s"$path.versionRef"))
      val installDir        = acc(requiredString(map, s"$path.installDir"))
      val createDirectories =
        acc(optionalStringList(map, "createDirectories", s"$path.createDirectories"))
      val download          = acc(decodeDownload(requiredMap(map, s"$path.download"), path))
      acc(unsupportedInstaller(map, s"$path.installer"))
      val executables = acc(decodeExecutables(requiredList(map, s"$path.executables"), path))
      val symlinks    = acc(decodeSymlinks(optionalList(map, "symlinks", s"$path.symlinks"), path))
      BinaryToolSpec(
        versionRef = versionRef,
        installDir = installDir,
        createDirectories = createDirectories,
        download = download,
        executables = executables,
        symlinks = symlinks
      )

  private def decodeDownload(
      input: DecodeResult[YamlMap],
      specPath: String
  ): DecodeResult[DownloadSpec] =
    val path = s"$specPath.download"
    DecodeResult.accumulate: acc =>
      val map = acc(input)
      acc.report(unknownKeyErrors(map, path, Set("url", "filename", "checksum", "archive")))
      val url      = acc(requiredString(map, s"$path.url"))
      val filename = acc(requiredString(map, s"$path.filename"))
      val checksum = acc(optionalChecksum(map, s"$path.checksum"))
      val archive  = acc(optionalArchive(map, s"$path.archive"))
      DownloadSpec(url, filename, checksum, archive)

  private def optionalChecksum(map: YamlMap, path: String): DecodeResult[Option[ChecksumSpec]] =
    optionalBlock(map, "checksum", path, Set("algorithm", "value", "discover")):
      (checksumMap, acc) =>
        val algorithm = acc(enumValue(
          requiredString(checksumMap, s"$path.algorithm"),
          s"$path.algorithm",
          ChecksumAlgorithm.values.toVector,
          ChecksumAlgorithm.Sha256,
          _.value
        ))
        val rawChecksum = acc(optionalString(checksumMap, "value", s"$path.value"))
        val discover    = acc(optionalChecksumDiscovery(checksumMap, s"$path.discover"))
        // The raw Option decides the "value or discover" shape rules; parsing happens after, so a
        // malformed digest is reported as a bad digest rather than as a missing one.
        acc.report(checksumShapeErrors(path, rawChecksum, discover))
        val checksum = rawChecksum.flatMap: value =>
          Sha256Digest.fromString(value) match
            case Right(digest) => Some(digest)
            case Left(_)       =>
              acc.report(Vector(ValidationError(
                s"$path.value",
                "sha256 checksum must be 64 hexadecimal characters"
              )))
              None
        ChecksumSpec(algorithm, checksum, discover)

  private def optionalChecksumDiscovery(
      map: YamlMap,
      path: String
  ): DecodeResult[Option[ChecksumDiscoverySpec]] =
    optionalBlock(map, "discover", path, Set("type", "url", "file")): (sourceMap, acc) =>
      val kind = acc(enumValue(
        requiredString(sourceMap, s"$path.type"),
        s"$path.type",
        ChecksumDiscoveryKind.values.toVector,
        ChecksumDiscoveryKind.Sha256Sum,
        _.value
      ))
      val url  = acc(requiredString(sourceMap, s"$path.url"))
      val file = acc(optionalString(sourceMap, "file", s"$path.file"))
      ChecksumDiscoverySpec(kind, url, file)

  private def checksumShapeErrors(
      path: String,
      value: Option[String],
      discover: Option[ChecksumDiscoverySpec]
  ): Vector[ValidationError] = (value, discover) match
    case (Some(_), Some(_)) =>
      Vector(ValidationError(path, "checksum must declare either value or discover, not both"))
    case (None, None) => Vector(ValidationError(path, "checksum must declare value or discover"))
    case _            => Vector.empty

  private def optionalArchive(map: YamlMap, path: String): DecodeResult[Option[ArchiveSpec]] =
    optionalBlock(map, "archive", path, Set("type", "extract")): (archiveMap, acc) =>
      val archiveType = acc(enumValue(
        requiredString(archiveMap, s"$path.type"),
        s"$path.type",
        ArchiveType.values.toVector,
        ArchiveType.Zip,
        _.value
      ))
      val extract = acc(decodeArchiveExtract(requiredMap(archiveMap, s"$path.extract"), path))
      ArchiveSpec(archiveType, extract)

  private def decodeArchiveExtract(
      input: DecodeResult[YamlMap],
      archivePath: String
  ): DecodeResult[ArchiveExtract] =
    DecodeResult.accumulate: acc =>
      val map = acc(input)
      acc.report(unknownKeyErrors(map, s"$archivePath.extract", Set("files", "directories")))
      val files = acc(decodeExtractMappings(
        optionalList(map, "files", s"$archivePath.extract.files"),
        s"$archivePath.extract.files"
      ))
      val directories = acc(decodeExtractMappings(
        optionalList(map, "directories", s"$archivePath.extract.directories"),
        s"$archivePath.extract.directories"
      ))
      ArchiveExtract(files, directories)

  private def decodeExtractMappings(
      input: DecodeResult[Vector[Any]],
      path: String
  ): DecodeResult[Vector[ExtractMapping]] =
    decodeItems(input, path, Set("from", "to")): (item, itemPath, acc) =>
      val from = acc(requiredString(item, s"$itemPath.from"))
      val to   = acc(requiredString(item, s"$itemPath.to"))
      ExtractMapping(from, to)

  private def unsupportedInstaller(map: YamlMap, path: String): DecodeResult[Unit] =
    map.get("installer") match
      case None    => DecodeResult.valid(())
      case Some(_) => DecodeResult.invalid(
          (),
          path,
          // Installer scripts are deliberately rejected at config load so no later boundary has
          // to decide whether arbitrary manifest text is executable.
          "installer scripts are not supported; use direct binary or archive download"
        )

  private def decodeExecutables(
      input: DecodeResult[Vector[Any]],
      specPath: String
  ): DecodeResult[Vector[ExecutableSpec]] =
    decodeItems(input, s"$specPath.executables", Set("path", "mode")): (item, itemPath, acc) =>
      val file = acc(requiredString(item, s"$itemPath.path"))
      val mode = acc(optionalMode(item, s"$itemPath.mode"))
      ExecutableSpec(file, mode)

  private def decodeSymlinks(
      input: DecodeResult[Vector[Any]],
      specPath: String
  ): DecodeResult[Vector[SymlinkSpec]] =
    decodeItems(input, s"$specPath.symlinks", Set("path", "target", "sudo")):
      (item, itemPath, acc) =>
        val file   = acc(requiredString(item, s"$itemPath.path"))
        val target = acc(requiredString(item, s"$itemPath.target"))
        val sudo = acc(optionalBoolean(item, "sudo", s"$itemPath.sudo", default = false)
          .map(SymlinkPrivilege.fromBoolean))
        SymlinkSpec(file, target, sudo)

  private def optionalMode(map: YamlMap, path: String): DecodeResult[Option[ExecutableMode]] =
    map.get("mode") match
      case None                => DecodeResult.valid(None)
      case Some(value: String) => ExecutableMode.fromString(value) match
          case Right(mode)   => DecodeResult.valid(Some(mode))
          case Left(message) => DecodeResult.invalid(None, path, message)
      case Some(_) => DecodeResult.invalid(None, path, "mode must be a string")

  private def optionalPolicyOverride(
      map: YamlMap,
      key: String,
      path: String
  ): DecodeResult[Option[PolicyOverride]] = map.get(key) match
    case None                 => DecodeResult.valid(None)
    case Some(value: Boolean) => DecodeResult.valid(Some(PolicyOverride.fromBoolean(value)))
    case Some(_)              => DecodeResult.invalid(None, path, "value must be a boolean")

  /** Decode an optional nested block, rejecting unknown keys inside it.
   *
   *  Every optional block in this manifest follows the same shape: absent means `None`; present
   *  means it must be a map, its keys must all be recognised, and only then are its fields read.
   *  Spelling that out per block is how a new block quietly acquires an unchecked key set — the
   *  decoder still compiles and still works, it just silently accepts typos, which is the one
   *  thing this file exists to prevent.
   */
  private def optionalBlock[A](
      map: YamlMap,
      key: String,
      path: String,
      allowed: Set[String]
  )(decode: (YamlMap, DecodeResult.Accumulator) => A): DecodeResult[Option[A]] =
    map.get(key) match
      case None        => DecodeResult.valid(None)
      case Some(value) =>
        DecodeResult.accumulate: acc =>
          val blockMap = acc(asMap(value, path))
          acc.report(unknownKeyErrors(blockMap, path, allowed))
          Some(decode(blockMap, acc))

  /** Decode a list of uniform items, rejecting unknown keys inside each one.
   *
   *  The item path (`<path>[<index>]`) is built once and handed to the caller, rather than being
   *  re-interpolated for the item, its unknown-key report and each of its fields — which is where
   *  an index or a label goes wrong without any test noticing, because the value still decodes
   *  and only the path in the error message is off.
   */
  private def decodeItems[A](
      input: DecodeResult[Vector[Any]],
      path: String,
      allowed: Set[String]
  )(decodeItem: (YamlMap, String, DecodeResult.Accumulator) => A): DecodeResult[Vector[A]] =
    DecodeResult.accumulate: acc =>
      val list = acc(input)
      list.zipWithIndex.map:
        case (value, index) =>
          val itemPath = s"$path[$index]"
          val item     = acc(asMap(value, itemPath))
          acc.report(unknownKeyErrors(item, itemPath, allowed))
          decodeItem(item, itemPath, acc)

  private def unknownKeyErrors(
      map: YamlMap,
      path: String,
      allowed: Set[String]
  ): Vector[ValidationError] = map.keySet.diff(allowed).toVector.sorted.map: key =>
    val fieldPath = if path == "$" then key else s"$path.$key"
    ValidationError(fieldPath, s"unknown field '$key'")
