package binstaller.core

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

  /** `indexedTools` pairs each tool with its `spec.plan` index, which the error paths name. */
  def validate(
      profile: BinaryDistributionProfile,
      policy: ResolvedPolicy,
      indexedTools: Vector[(ResolvedTool, Int)],
      activeVersionRefs: Set[String]
  ): Vector[ValidationError] = dynamicLatestUrlErrors(profile, policy, activeVersionRefs) ++
    latestDownloadUrlErrors(policy, indexedTools) ++
    missingChecksumErrors(policy, indexedTools)

  private def dynamicLatestUrlErrors(
      profile: BinaryDistributionProfile,
      policy: ResolvedPolicy,
      activeVersionRefs: Set[String]
  ): Vector[ValidationError] = policy.allowDynamicLatestUrls match
    case PolicyAllowance.Allowed  => Vector.empty
    case PolicyAllowance.Rejected =>
      // A rejection has two causes: the strict default, or an explicit `false`, which also
      // applies in developer mode. Naming the wrong one sends the user to the wrong setting.
      val rejectedBy =
        if profile.spec.policy.allowDynamicLatestUrls.isDefined then
          "spec.policy.allowDynamicLatestUrls: false"
        else s"policy.mode=${policy.mode.value}"
      profile.spec.versions.toVector.collect:
        case (name, VersionSource.Dynamic(DynamicVersionKind.LatestUrl, _))
            if activeVersionRefs(name) =>
          strictError(
            s"spec.versions.$name.dynamic.type",
            "dynamic-latest-url",
            s"dynamic latest-url version sources are rejected by $rejectedBy",
            s"pin spec.versions.$name, use a resolver with locked provenance, or set " +
              "spec.policy.allowDynamicLatestUrls: true"
          )

  private def latestDownloadUrlErrors(
      policy: ResolvedPolicy,
      indexedTools: Vector[(ResolvedTool, Int)]
  ): Vector[ValidationError] = policy.allowDynamicLatestUrls match
    case PolicyAllowance.Allowed  => Vector.empty
    case PolicyAllowance.Rejected => indexedTools.collect:
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
      indexedTools: Vector[(ResolvedTool, Int)]
  ): Vector[ValidationError] = policy.allowMissingChecksums match
    case PolicyAllowance.Allowed  => Vector.empty
    case PolicyAllowance.Rejected => indexedTools.collect:
        case (tool, index) if tool.download.checksum.isEmpty =>
          strictError(
            s"spec.plan[$index].spec.download.checksum",
            "missing-checksum",
            s"tool '${tool.name}' has no sha256 checksum",
            "add spec.plan[].spec.download.checksum or set spec.policy.allowMissingChecksums: true"
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
    lower.endsWith("/latest") || lower.contains("/latest/") ||
    lower.contains("/latest?") || lower.contains("/latest#")
