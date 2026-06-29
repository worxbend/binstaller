package binstaller.core

import binstaller.config.ArchiveType
import binstaller.config.BinaryDistributionProfile
import binstaller.config.DynamicVersionKind
import binstaller.config.PolicyMode
import binstaller.config.PolicyOverride
import binstaller.config.ValidationError
import binstaller.config.VersionSource

private[core] object ManifestPolicy:

  def allowance(
      mode: PolicyMode,
      overrideValue: Option[PolicyOverride]
  ): PolicyAllowance = overrideValue match
    case Some(PolicyOverride.Enabled)  => PolicyAllowance.Allowed
    case Some(PolicyOverride.Disabled) => PolicyAllowance.Rejected
    case None                          => mode match
        case PolicyMode.Developer => PolicyAllowance.Allowed
        case PolicyMode.Strict    => PolicyAllowance.Rejected

private[core] object StrictPolicyValidator:

  def validate(
      profile: BinaryDistributionProfile,
      policy: ResolvedPolicy,
      tools: Vector[ResolvedTool]
  ): Vector[ValidationError] = dynamicLatestUrlErrors(profile, policy) ++
    latestDownloadUrlErrors(policy, tools) ++
    missingChecksumErrors(policy, tools) ++
    tarXzFallbackErrors(policy, tools)

  private def dynamicLatestUrlErrors(
      profile: BinaryDistributionProfile,
      policy: ResolvedPolicy
  ): Vector[ValidationError] = policy.allowDynamicLatestUrls match
    case PolicyAllowance.Allowed  => Vector.empty
    case PolicyAllowance.Rejected => profile.spec.versions.toVector.collect:
        case (name, VersionSource.Dynamic(DynamicVersionKind.LatestUrl, _)) => strictError(
            s"spec.versions.$name.dynamic.type",
            "dynamic-latest-url",
            "dynamic latest-url version sources are rejected by policy.mode=strict",
            s"pin spec.versions.$name, use a resolver with locked provenance, or set " +
              "spec.policy.allowDynamicLatestUrls: true"
          )

  private def latestDownloadUrlErrors(
      policy: ResolvedPolicy,
      tools: Vector[ResolvedTool]
  ): Vector[ValidationError] = policy.allowDynamicLatestUrls match
    case PolicyAllowance.Allowed  => Vector.empty
    case PolicyAllowance.Rejected => tools.zipWithIndex.collect:
        case (tool, index) if usesLatestEndpoint(tool.download.url) =>
          strictError(
            s"spec.plan[$index].spec.download.url",
            "dynamic-latest-url",
            s"tool '${tool.name}' downloads from a latest endpoint",
            "pin the download URL to an immutable release path or set " +
              "spec.policy.allowDynamicLatestUrls: true"
          )

  private def missingChecksumErrors(
      policy: ResolvedPolicy,
      tools: Vector[ResolvedTool]
  ): Vector[ValidationError] = policy.allowMissingChecksums match
    case PolicyAllowance.Allowed  => Vector.empty
    case PolicyAllowance.Rejected => tools.zipWithIndex.collect:
        case (tool, index) if tool.download.checksum.isEmpty =>
          strictError(
            s"spec.plan[$index].spec.download.checksum",
            "missing-checksum",
            s"tool '${tool.name}' has no sha256 checksum",
            "add spec.plan[].spec.download.checksum or set spec.policy.allowMissingChecksums: true"
          )

  private def tarXzFallbackErrors(
      policy: ResolvedPolicy,
      tools: Vector[ResolvedTool]
  ): Vector[ValidationError] = policy.allowTarXzFallback match
    case PolicyAllowance.Allowed  => Vector.empty
    case PolicyAllowance.Rejected => tools.zipWithIndex.collect:
        case (tool, index)
            if tool.download.archive.exists(_.original.archiveType == ArchiveType.TarXz) =>
          strictError(
            s"spec.plan[$index].spec.download.archive.type",
            "tar-xz-fallback",
            s"tool '${tool.name}' uses the system tar.xz fallback extractor",
            "use zip or tar.gz, add native tar.xz support, or set spec.policy.allowTarXzFallback: true"
          )

  private def strictError(
      path: String,
      code: String,
      reason: String,
      suggestion: String
  ): ValidationError = ValidationError(
    path,
    s"strict-policy[$code]: $reason; suggestion[$code]: $suggestion"
  )

  private def usesLatestEndpoint(url: String): Boolean =
    val lower = url.toLowerCase
    lower.endsWith("/latest") || lower.contains("/latest/")
