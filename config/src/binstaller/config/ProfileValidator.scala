package binstaller.config

private[config] object ProfileValidator:

  def validate(profile: BinaryDistributionProfile): Vector[ValidationError] =
    metadataNameErrors(profile) ++ duplicateToolNameErrors(profile) ++
      unknownVersionRefErrors(profile) ++
      sudoSymlinkErrors(profile)

  private def metadataNameErrors(profile: BinaryDistributionProfile): Vector[ValidationError] =
    unsafeToolNameMessage(profile.metadata.name)
      .map(message => ValidationError("metadata.name", message))
      .toVector

  // Tool names are validated by the decoder now. `metadata.name` is still a String and this is
  // its only check, so the helper stays.
  private def unsafeToolNameMessage(value: String): Option[String] =
    ToolName.fromString(value).left.toOption

  private def duplicateToolNameErrors(
      profile: BinaryDistributionProfile
  ): Vector[ValidationError] = profile.spec.plan
    .groupBy(_.name)
    .toVector
    .collect:
      case (name, entries) if entries.size > 1 =>
        ValidationError("spec.plan", s"duplicate tool name '$name'")

  private def unknownVersionRefErrors(
      profile: BinaryDistributionProfile
  ): Vector[ValidationError] =
    val versionNames = profile.spec.versions.keySet
    profile.spec.plan.zipWithIndex.collect:
      case (entry, index)
          if entry.spec.versionRef.nonEmpty && !versionNames(entry.spec.versionRef) =>
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
