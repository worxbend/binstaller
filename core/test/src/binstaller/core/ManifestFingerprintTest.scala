package binstaller.core

import binstaller.config.ApiVersion
import binstaller.config.ChecksumAlgorithm
import binstaller.config.ChecksumDiscoveryKind
import binstaller.config.ConfigModule
import binstaller.config.DynamicVersionKind
import binstaller.config.ManifestKind
import binstaller.config.PlanKind
import binstaller.config.VersionResolverKind
import utest.*

import scala.compiletime.constValueTuple
import scala.deriving.Mirror

/** The 'edit the manifest and the lock stops matching' guarantee, field by field. */
object ManifestFingerprintTest extends TestSuite with CoreTestPrimitives:

  private inline def fields[T](typeName: String)(using mirror: Mirror.ProductOf[T]): Set[String] =
    constValueTuple[mirror.MirroredElemLabels].toList.map(label => s"$typeName.$label").toSet

  /** Every field of the config types that describe a manifest, read from the types themselves. */
  private val configFields: Set[String] =
    fields[binstaller.config.BinaryDistributionProfile]("BinaryDistributionProfile") ++
      fields[binstaller.config.ManifestMetadata]("ManifestMetadata") ++
      fields[binstaller.config.ProfileSpec]("ProfileSpec") ++
      fields[binstaller.config.InstallPolicy]("InstallPolicy") ++
      fields[binstaller.config.PlanEntry]("PlanEntry") ++
      fields[binstaller.config.WhenClause]("WhenClause") ++
      fields[binstaller.config.OsClause]("OsClause") ++
      fields[binstaller.config.BinaryToolSpec]("BinaryToolSpec") ++
      fields[binstaller.config.DownloadSpec]("DownloadSpec") ++
      fields[binstaller.config.ChecksumSpec]("ChecksumSpec") ++
      fields[binstaller.config.ChecksumDiscoverySpec]("ChecksumDiscoverySpec") ++
      fields[binstaller.config.ArchiveSpec]("ArchiveSpec") ++
      fields[binstaller.config.ArchiveExtract]("ArchiveExtract") ++
      fields[binstaller.config.ExtractMapping]("ExtractMapping") ++
      fields[binstaller.config.ExecutableSpec]("ExecutableSpec") ++
      fields[binstaller.config.SymlinkSpec]("SymlinkSpec") ++
      fields[binstaller.config.VersionSource.Pinned]("VersionSource.Pinned") ++
      fields[binstaller.config.VersionSource.Dynamic]("VersionSource.Dynamic") ++
      fields[binstaller.config.VersionSource.Resolver]("VersionSource.Resolver")

  private val alphaChecksum = "a" * 64

  private val betaEntry: String = """    - name: beta
                                    |      kind: binary-tool
                                    |      spec:
                                    |        versionRef: fetched
                                    |        installDir: "${appsDir}/beta"
                                    |        download:
                                    |          url: https://example.invalid/beta
                                    |          filename: beta
                                    |          checksum:
                                    |            algorithm: sha256
                                    |            discover:
                                    |              type: sha256sum
                                    |              url: https://example.invalid/SHA256SUMS
                                    |              file: beta-linux-amd64
                                    |        executables:
                                    |          - path: bin/beta
                                    |""".stripMargin

  /** A manifest that sets every field once, with text distinct enough to edit one at a time. */
  private val baseYaml: String = s"""apiVersion: binstaller.io/v1alpha1
                                    |kind: BinaryDistributionProfile
                                    |metadata:
                                    |  name: fingerprint-base
                                    |  labels:
                                    |    tier: dev
                                    |  annotations:
                                    |    owner: platform
                                    |spec:
                                    |  policy:
                                    |    mode: developer
                                    |    continueOnError: false
                                    |    appsDir: "$${HOME}/.apps"
                                    |    allowSudoSymlinks: true
                                    |    allowDynamicLatestUrls: true
                                    |    allowMissingChecksums: true
                                    |    stateFile: base.state.json
                                    |  vars:
                                    |    arch: amd64
                                    |  versions:
                                    |    pinned: "1.0.0"
                                    |    floating:
                                    |      dynamic:
                                    |        type: latest-url
                                    |        note: upstream latest
                                    |    fetched:
                                    |      resolver:
                                    |        type: http-text
                                    |        url: https://example.invalid/fetched-version
                                    |  plan:
                                    |    - name: alpha
                                    |      kind: binary-tool
                                    |      description: first tool
                                    |      when:
                                    |        os:
                                    |          family: linux
                                    |        architecture: x86_64
                                    |      spec:
                                    |        versionRef: pinned
                                    |        installDir: "$${appsDir}/alpha"
                                    |        createDirectories:
                                    |          - share
                                    |        download:
                                    |          url: https://example.invalid/alpha.tar.gz
                                    |          filename: alpha.tar.gz
                                    |          checksum:
                                    |            algorithm: sha256
                                    |            value: $alphaChecksum
                                    |          archive:
                                    |            type: tar.gz
                                    |            extract:
                                    |              files:
                                    |                - from: pkg/alpha
                                    |                  to: bin/alpha
                                    |              directories:
                                    |                - from: pkg/share
                                    |                  to: share/alpha
                                    |        executables:
                                    |          - path: bin/alpha
                                    |            mode: "0755"
                                    |        symlinks:
                                    |          - path: bin/link
                                    |            target: libexec/alpha
                                    |$betaEntry""".stripMargin

  private val osBlock: String = """        os:
                                  |          family: linux
                                  |""".stripMargin

  private val whenBlock: String = s"""      when:
                                     |$osBlock        architecture: x86_64
                                     |""".stripMargin

  private val checksumBlock: String = s"""          checksum:
                                         |            algorithm: sha256
                                         |            value: $alphaChecksum
                                         |""".stripMargin

  private val filesBlock: String = """              files:
                                     |                - from: pkg/alpha
                                     |                  to: bin/alpha
                                     |""".stripMargin

  private val directoriesBlock: String = """              directories:
                                           |                - from: pkg/share
                                           |                  to: share/alpha
                                           |""".stripMargin

  private val archiveBlock: String = s"""          archive:
                                        |            type: tar.gz
                                        |            extract:
                                        |$filesBlock$directoriesBlock""".stripMargin

  private val executableMode = "            mode: \"0755\"\n"

  private val symlinkTarget = "            target: libexec/alpha\n"

  private val symlinksBlock: String = s"""        symlinks:
                                         |          - path: bin/link
                                         |$symlinkTarget""".stripMargin

  /** One manifest edit: the text to find in [[baseYaml]] and the text that replaces it. */
  private final case class FieldEdit(field: String, from: String, to: String)

  /**
   * One edit per config field.
   *
   * A scalar field changes its value. A field that holds a list, a map or an optional block gains
   * or loses an element, because that is the edit a user makes to it.
   */
  private val fieldEdits: Vector[FieldEdit] = Vector(
    FieldEdit("ManifestMetadata.name", "name: fingerprint-base", "name: fingerprint-edited"),
    FieldEdit("ManifestMetadata.labels", "tier: dev", "tier: prod"),
    FieldEdit("ManifestMetadata.annotations", "owner: platform", "owner: nobody"),
    FieldEdit("ProfileSpec.vars", "arch: amd64", "arch: arm64"),
    FieldEdit("ProfileSpec.versions", "  versions:\n", "  versions:\n    extra: \"9.9.9\"\n"),
    FieldEdit("ProfileSpec.plan", betaEntry, ""),
    FieldEdit("InstallPolicy.mode", "mode: developer", "mode: strict"),
    FieldEdit("InstallPolicy.continueOnError", "continueOnError: false", "continueOnError: true"),
    FieldEdit("InstallPolicy.appsDir", "${HOME}/.apps", "${HOME}/.tools"),
    FieldEdit(
      "InstallPolicy.allowSudoSymlinks",
      "allowSudoSymlinks: true",
      "allowSudoSymlinks: false"
    ),
    FieldEdit(
      "InstallPolicy.allowDynamicLatestUrls",
      "allowDynamicLatestUrls: true",
      "allowDynamicLatestUrls: false"
    ),
    FieldEdit(
      "InstallPolicy.allowMissingChecksums",
      "allowMissingChecksums: true",
      "allowMissingChecksums: false"
    ),
    FieldEdit("InstallPolicy.stateFile", "stateFile: base.state", "stateFile: other.state"),
    FieldEdit("PlanEntry.name", "- name: alpha", "- name: omega"),
    FieldEdit("PlanEntry.description", "description: first tool", "description: renamed tool"),
    FieldEdit("PlanEntry.when", whenBlock, ""),
    FieldEdit("WhenClause.os", osBlock, ""),
    FieldEdit("WhenClause.architecture", "architecture: x86_64", "architecture: arm64"),
    FieldEdit("OsClause.family", "family: linux", "family: darwin"),
    FieldEdit("BinaryToolSpec.versionRef", "versionRef: pinned", "versionRef: floating"),
    FieldEdit("BinaryToolSpec.installDir", "${appsDir}/alpha", "${appsDir}/alpha-next"),
    FieldEdit("BinaryToolSpec.createDirectories", "- share\n", "- share\n          - doc\n"),
    FieldEdit(
      "BinaryToolSpec.executables",
      executableMode,
      s"$executableMode          - path: bin/extra\n"
    ),
    FieldEdit("BinaryToolSpec.symlinks", symlinksBlock, ""),
    FieldEdit("DownloadSpec.url", "/alpha.tar.gz", "/alpha-2.tar.gz"),
    FieldEdit("DownloadSpec.filename", "filename: alpha.tar.gz", "filename: alpha-2.tar.gz"),
    FieldEdit("DownloadSpec.checksum", checksumBlock, ""),
    FieldEdit("DownloadSpec.archive", archiveBlock, ""),
    FieldEdit("ChecksumSpec.source", s"value: $alphaChecksum", s"value: ${"b" * 64}"),
    FieldEdit("ChecksumDiscoverySpec.url", "/SHA256SUMS", "/SHA256SUMS.txt"),
    FieldEdit("ChecksumDiscoverySpec.file", "file: beta-linux-amd64", "file: beta-linux-arm64"),
    FieldEdit("ArchiveSpec.archiveType", "type: tar.gz", "type: tar.xz"),
    FieldEdit("ArchiveExtract.files", filesBlock, ""),
    FieldEdit("ArchiveExtract.directories", directoriesBlock, ""),
    FieldEdit("ExtractMapping.from", "from: pkg/alpha", "from: pkg/alpha-bin"),
    FieldEdit("ExtractMapping.to", "to: bin/alpha", "to: bin/alpha-bin"),
    FieldEdit("ExecutableSpec.path", "- path: bin/alpha", "- path: bin/alpha-renamed"),
    FieldEdit("ExecutableSpec.mode", "mode: \"0755\"", "mode: \"0700\""),
    FieldEdit("SymlinkSpec.path", "- path: bin/link", "- path: bin/link-renamed"),
    FieldEdit("SymlinkSpec.target", "target: libexec/alpha", "target: libexec/alpha-next"),
    FieldEdit("SymlinkSpec.privilege", symlinkTarget, s"$symlinkTarget            sudo: true\n"),
    FieldEdit("VersionSource.Pinned.value", "pinned: \"1.0.0\"", "pinned: \"1.0.1\""),
    FieldEdit("VersionSource.Dynamic.note", "note: upstream latest", "note: other note"),
    FieldEdit("VersionSource.Resolver.url", "/fetched-version", "/fetched-version-2")
  )

  /**
   * Fields whose type has one legal value today, so no manifest edit can change them.
   *
   * The count is read from the type: a second value fails the test until the field gets an edit.
   */
  private val singleValuedFields: Map[String, Int] = Map(
    "BinaryDistributionProfile.apiVersion" -> ApiVersion.values.length,
    "BinaryDistributionProfile.kind"       -> ManifestKind.values.length,
    "PlanEntry.kind"                       -> PlanKind.values.length,
    "ChecksumSpec.algorithm"               -> ChecksumAlgorithm.values.length,
    "ChecksumDiscoverySpec.kind"           -> ChecksumDiscoveryKind.values.length,
    "VersionSource.Dynamic.kind"           -> DynamicVersionKind.values.length,
    "VersionSource.Resolver.kind"          -> VersionResolverKind.values.length
  )

  /**
   * Fields that only hold another type listed in [[configFields]], whose fields carry the edits.
   */
  private val nestedOnlyFields: Set[String] = Set(
    "BinaryDistributionProfile.metadata",
    "BinaryDistributionProfile.spec",
    "ProfileSpec.policy",
    "PlanEntry.spec",
    "BinaryToolSpec.download",
    "ArchiveSpec.extract"
  )

  private def fingerprintOf(yaml: String, edit: String): String =
    ConfigModule.loadString(yaml) match
      case Right(profile) => ManifestFingerprint.profile(profile)
      case Left(error)    => abort(s"$edit: expected a manifest that loads, got $error")

  private def editedYaml(edit: FieldEdit): String =
    val first = baseYaml.indexOf(edit.from)
    if first < 0 || first != baseYaml.lastIndexOf(edit.from) then
      abort(s"${edit.field}: the edit must match the base manifest exactly once")
    else baseYaml.replace(edit.from, edit.to)

  val tests: Tests = Tests:
    test("the fingerprint of a known manifest never changes"):
      // Lock and state files store this value. A different hash for the same manifest would
      // refuse every existing lock file and reinstall every tool recorded in a state file.
      assert(fingerprintOf(baseYaml, "base") ==
        "cb09a745b87eedfdbd6fd7e96756a20b1d8639c15b34730709ec380bce944c4d")

    test("every config field has a fingerprint edit, or cannot be edited on its own"):
      val accountedFor = fieldEdits.map(_.field).toSet ++ singleValuedFields.keySet ++
        nestedOnlyFields

      assert(configFields == accountedFor)
      assert(singleValuedFields.filter((_, legalValues) => legalValues != 1).isEmpty)

    test("editing any one manifest field changes the fingerprint"):
      val baseFingerprint = fingerprintOf(baseYaml, "base")

      val unchanged = fieldEdits.collect:
        case edit if fingerprintOf(editedYaml(edit), edit.field) == baseFingerprint => edit.field

      assert(unchanged == Vector.empty)
