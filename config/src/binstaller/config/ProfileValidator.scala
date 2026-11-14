package binstaller.config

private[config] object ProfileValidator:

  def validate(
      profile: BinaryDistributionProfile,
      failedDecoderPaths: Set[String] = Set.empty
  ): Vector[ValidationError] =
    // A failed decode leaves a placeholder at its path and below it. A check that reads one, or
    // an error that points at one, is about a value the user never wrote.
    def isPlaceholder(path: String): Boolean =
      failedDecoderPaths.exists(ManifestPath.isAtOrUnder(path, _))
    val versionRefErrors =
      if isPlaceholder("spec.versions") then Vector.empty else unknownVersionRefErrors(profile)
    val sudoErrors =
      if isPlaceholder("spec.policy.allowSudoSymlinks") then Vector.empty
      else sudoSymlinkErrors(profile)
    val errors = metadataNameErrors(profile) ++
      duplicateToolNameErrors(profile, isPlaceholder) ++
      versionRefErrors ++
      sudoErrors ++
      whenSelectorErrors(profile)
    errors.filterNot(error => isPlaceholder(error.path))

  private def metadataNameErrors(profile: BinaryDistributionProfile): Vector[ValidationError] =
    ToolName.unsafeNameReason(profile.metadata.name)
      .map(reason => ValidationError("metadata.name", s"profile name $reason"))
      .toVector

  private def duplicateToolNameErrors(
      profile: BinaryDistributionProfile,
      isPlaceholder: String => Boolean
  ): Vector[ValidationError] = profile.spec.plan.zipWithIndex
    .collect:
      case (entry, index) if !isPlaceholder(ManifestPath.planEntryName(index)) =>
        entry.name -> index
    .groupBy((name, _) => name)
    .toVector
    .sortBy((_, entries) => entries.map((_, index) => index).min)
    .collect:
      case (name, entries) if entries.size > 1 =>
        ValidationError("spec.plan", s"duplicate tool name '$name'")

  private def unknownVersionRefErrors(
      profile: BinaryDistributionProfile
  ): Vector[ValidationError] =
    val versionNames = profile.spec.versions.keySet
    profile.spec.plan.zipWithIndex.collect:
      case (entry, index) if !versionNames(entry.spec.versionRef) =>
        ValidationError(
          s"spec.plan[$index].spec.versionRef",
          s"tool '${entry.name}' references unknown version '${entry.spec.versionRef}'"
        )

  private def sudoSymlinkErrors(profile: BinaryDistributionProfile): Vector[ValidationError] =
    profile.spec.policy.allowSudoSymlinks match
      case PolicyOverride.Enabled  => Vector.empty
      case PolicyOverride.Disabled => profile.spec.plan.zipWithIndex.flatMap:
          case (entry, entryIndex) => entry.spec.symlinks.zipWithIndex.collect:
              case (symlink, symlinkIndex) if symlink.privilege == SymlinkPrivilege.Sudo =>
                ValidationError(
                  s"spec.plan[$entryIndex].spec.symlinks[$symlinkIndex].sudo",
                  sudoSymlinkPolicyMessage(profile, entry.name)
                )

  // A selector outside the vocabulary equals no host, so its entry would be left out of every
  // plan without a message.
  private def whenSelectorErrors(profile: BinaryDistributionProfile): Vector[ValidationError] =
    profile.spec.plan.zipWithIndex.flatMap: (entry, index) =>
      val path        = s"${ManifestPath.planEntry(index)}.when"
      val familyError = entry.when.flatMap(_.os).flatMap(_.family)
        .filter(HostVocabulary.osFamily(_).isEmpty)
        .map(unsupportedSelector(s"$path.os.family", _, HostVocabulary.osFamilyExpectation))
      val architectureError = entry.when.flatMap(_.architecture)
        .filter(HostVocabulary.architecture(_).isEmpty)
        .map(unsupportedSelector(s"$path.architecture", _, HostVocabulary.architectureExpectation))
      familyError.toVector ++ architectureError.toVector

  private def unsupportedSelector(path: String, value: String, expectation: String)
      : ValidationError =
    ValidationError(path, s"unsupported value '$value', expected $expectation")

  private def sudoSymlinkPolicyMessage(
      profile: BinaryDistributionProfile,
      toolName: ToolName
  ): String = profile.spec.policy.mode match
    case PolicyMode.Strict =>
      s"strict-policy[sudo-symlink]: tool '$toolName' uses a sudo symlink; " +
        "suggestion[allow-sudo-symlinks]: set spec.policy.allowSudoSymlinks: true " +
        "only for reviewed system symlinks"
    case PolicyMode.Developer =>
      s"tool '$toolName' uses a sudo symlink but policy.allowSudoSymlinks is false"
