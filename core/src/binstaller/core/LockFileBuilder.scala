package binstaller.core

private[core] final case class LockBuildError(toolName: String, message: String)

private[core] object LockFileBuilder:

  def build(
      prepared: PreparedPlan,
      metadataClient: BinaryMetadataClient
  ): Either[LockBuildError, LockFile] = collectEither(
    prepared.plan.tools.map(tool => toolEntry(tool, metadataClient))
  ).map: tools =>
    LockFile(
      LockFile.schemaVersion,
      prepared.profileName,
      prepared.manifestFingerprint,
      tools
    )

  private def toolEntry(
      tool: ResolvedTool,
      metadataClient: BinaryMetadataClient
  ): Either[LockBuildError, LockFileTool] = metadataClient.metadata(tool.download.url)
    .left
    .map(error => LockBuildError(tool.name, error.message))
    .flatMap: metadata =>
      metadata.sha256
        .toRight(LockBuildError(tool.name, "artifact inspection returned no sha256 digest"))
        .flatMap: digest =>
          verifiedChecksum(tool, digest).map: checksum =>
            val (resolvedVersion, versionProvenance, dynamicSource) = versionFields(tool.version)
            LockFileTool(
              name = tool.name,
              resolvedVersion = resolvedVersion,
              versionProvenance = versionProvenance,
              downloadProvenance = metadata.provenance,
              sizeBytes = metadata.sizeBytes,
              checksum = Some(checksum),
              dynamicSource = dynamicSource
            )

  private def verifiedChecksum(
      tool: ResolvedTool,
      actual: Sha256Digest
  ): Either[LockBuildError, LockFileChecksum] = tool.download.checksum match
    case None => Right(LockFileChecksum(
        "sha256",
        actual.value,
        "inspected",
        None,
        None,
        None
      ))
    case Some(configured) if configured.value.equalsIgnoreCase(actual.value) =>
      Right(LockFileChecksum.fromResolved(configured))
    case Some(configured) => Left(LockBuildError(
        tool.name,
        s"artifact sha256 ${actual.value} does not match declared sha256 ${configured.value}"
      ))

  private def versionFields(
      version: ResolvedVersion
  ): (Option[String], Option[UrlProvenance], Boolean) = version match
    case ResolvedVersion.Concrete(value, provenance) => (Some(value), provenance, false)
    case ResolvedVersion.DynamicLatestUrl(_)         => (None, None, true)

  private def collectEither[A](
      values: Vector[Either[LockBuildError, A]]
  ): Either[LockBuildError, Vector[A]] = values.foldLeft(
    Right(Vector.empty): Either[LockBuildError, Vector[A]]
  ): (acc, next) =>
    for
      current <- acc
      value   <- next
    yield current :+ value
