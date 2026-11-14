package binstaller.config

import utest.*

import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

object ConfigModuleTest extends TestSuite:

  val tests: Tests = Tests:
    test("throwable diagnostics never render as the literal null"):
      // getMessage is nullable, and these are exceptions the program actually hits. Rendering one
      // straight into a diagnostic produces user-facing output like "staging: null".
      assert(Diagnostics.describe(java.io.IOException()) == "java.io.IOException")
      assert(Diagnostics.describe(RuntimeException("boom")) == "boom")
      // An explicit null message is required: RuntimeException(cause) alone derives getMessage
      // from the cause's toString, so it would never exercise the fallback.
      assert(
        Diagnostics.describe(RuntimeException(null, IllegalStateException("inner"))) == "inner"
      )
      // A cause without its own message must not fall back to the outer class name.
      assert(
        Diagnostics.describe(RuntimeException(null, IllegalStateException())) ==
          "java.lang.IllegalStateException"
      )

    test("an uppercase manifest checksum is normalized to lowercase"):
      // The digest is normalized once, at decode, so the comparison that decides whether a
      // downloaded binary is trusted is a plain `==` rather than a case-insensitive compare of a
      // normalized value against an unnormalized one.
      val upper = "0A745198DE24545D0055CD8414BC8D2BA10363EF5F5D38369EA1B399671CC083"
      assert(Sha256Digest.fromString(upper).map(_.value) == Right(upper.toLowerCase))
      assert(Sha256Digest.fromString("not-a-digest").isLeft)

    test("literal checksum construction exposes only a literal source"):
      val value    = digest("a" * 64)
      val checksum = ChecksumSpec(ChecksumAlgorithm.Sha256, value)

      assert(checksum.source == ChecksumSource.Literal(value))
      assert(checksum.value.contains(value))
      assert(checksum.discover.isEmpty)

    test("discovery checksum construction exposes only a discovery source"):
      val source = ChecksumDiscoverySpec(
        ChecksumDiscoveryKind.Sha256Sum,
        "https://example.invalid/SHA256SUMS",
        Some("alpha")
      )
      val checksum = ChecksumSpec.discovery(ChecksumAlgorithm.Sha256, source)

      assert(checksum.source == ChecksumSource.Discovery(source))
      assert(checksum.value.isEmpty)
      assert(checksum.discover.contains(source))

    test("config example loads into typed manifest"):
      val profile = exampleProfile

      assert(profile.apiVersion == ApiVersion.V1Alpha1)
      assert(profile.kind == ManifestKind.BinaryDistributionProfile)
      assert(profile.metadata.name == "developer-binaries")
      assert(profile.spec.plan.size == 15)
      assert(profile.spec.versions.contains("kubectl"))
      assert(profile.spec.policy.mode == PolicyMode.Developer)
      assert(profile.spec.policy.allowSudoSymlinks == PolicyOverride.Enabled)

    test("policy defaults to developer behavior with overridable strict gates"):
      val developer = ConfigModule.loadString(policyModeYaml(""))
      val strict    = ConfigModule.loadString(policyModeYaml(
        """mode: strict
          |    allowDynamicLatestUrls: true
          |    allowMissingChecksums: false""".stripMargin
      ))

      developer match
        case Right(profile) =>
          assert(profile.spec.policy.mode == PolicyMode.Developer)
          assert(profile.spec.policy.allowDynamicLatestUrls.isEmpty)
          assert(profile.spec.policy.allowMissingChecksums.isEmpty)
        case Left(error) => abort(s"expected developer policy config, got $error")
      strict match
        case Right(profile) =>
          assert(profile.spec.policy.mode == PolicyMode.Strict)
          assert(profile.spec.policy.allowDynamicLatestUrls.contains(PolicyOverride.Enabled))
          assert(profile.spec.policy.allowMissingChecksums.contains(PolicyOverride.Disabled))
        case Left(error) => abort(s"expected strict policy config, got $error")

    test("config example locks binary tool order and version sources"):
      val profile = exampleProfile

      assert(profile.spec.plan.map(_.name.value) == exampleToolNames)
      assert(profile.spec.versions.keySet == exampleToolNames.toSet)
      assert(pinnedVersions(profile) == expectedPinnedVersions)
      assert(dynamicLatestUrlNames(profile) == expectedDynamicLatestUrlNames)
      assert(profile.spec.versions("kubectl") ==
        VersionSource.Resolver(
          VersionResolverKind.HttpText,
          "https://dl.k8s.io/release/stable.txt"
        ))

    test("config example keeps install directories under appsDir and records checksums"):
      val profile = exampleProfile

      assert(profile.spec.plan.map(entry => entry.name.value -> entry.spec.installDir) ==
        exampleToolNames.map(name => name -> s"$${appsDir}/$name"))
      assert(checksumFor(profile, "helm") ==
        Some(ChecksumSpec(
          ChecksumAlgorithm.Sha256,
          digest("0a745198de24545d0055cd8414bc8d2ba10363ef5f5d38369ea1b399671cc083")
        )))
      assert(checksumFor(profile, "kustomize") ==
        Some(ChecksumSpec(
          ChecksumAlgorithm.Sha256,
          digest("029a7f0f4e1932c52a0476cf02a0fd855c0bb85694b82c338fc648dcb53a819d")
        )))
      assert(checksumFor(profile, "dotbot") ==
        Some(ChecksumSpec(
          ChecksumAlgorithm.Sha256,
          digest("45d49e064d8684926fed97ad051c6ecebbf796a3c709edaa7a4a166b2978633d")
        )))
      assert(checksumFor(profile, "nerd-font-installer") ==
        Some(ChecksumSpec(
          ChecksumAlgorithm.Sha256,
          digest("25c70bcf327930282823fa6abecc54f14f53fb44b01f988187a07218b711a1a7")
        )))

    test("config example installs Helm from archive, not installer script"):
      val helm    = toolNamed(exampleProfile, "helm")
      val archive = helm.spec.download.archive.getOrElse(abort("missing Helm archive spec"))

      assert(helm.spec.download.url == "https://get.helm.sh/helm-${version}-linux-amd64.tar.gz")
      assert(archive.archiveType == ArchiveType.TarGz)
      assert(archive.extract.files.map(mapping => mapping.from -> mapping.to) ==
        Vector("linux-amd64/helm" -> "bin/helm"))
      assert(helm.spec.executables.map(_.path) == Vector("bin/helm"))

    test("config example installs Kustomize from archive, not installer script"):
      val kustomize = toolNamed(exampleProfile, "kustomize")
      val archive   =
        kustomize.spec.download.archive.getOrElse(abort("missing Kustomize archive spec"))

      assert(
        kustomize.spec.download.url ==
          "https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize/${version}/kustomize_${version}_linux_amd64.tar.gz"
      )
      assert(archive.archiveType == ArchiveType.TarGz)
      assert(archive.extract.files.map(mapping => mapping.from -> mapping.to) ==
        Vector("kustomize" -> "bin/kustomize"))
      assert(kustomize.spec.executables.map(_.path) == Vector("bin/kustomize"))

    test("config example includes Neovim sudo symlinks gated by policy"):
      val profile      = exampleProfile
      val neovim       = toolNamed(profile, "neovim")
      val sudoSymlinks = neovim.spec.symlinks.filter(_.privilege == SymlinkPrivilege.Sudo)

      assert(sudoSymlinks.map(_.path) == Vector(
        "/usr/local/bin/neovim",
        "/usr/local/bin/vim",
        "/usr/local/bin/nvim",
        "/usr/bin/nvim",
        "/usr/bin/neovim"
      ))
      assert(sudoSymlinks.map(_.target).distinct == Vector("${appsDir}/neovim/bin/nvim"))
      assert(exampleWithSudoPolicy(allowSudoSymlinks = true).isRight)
      val neovimIndex        = profile.spec.plan.indexWhere(_.name.value == "neovim")
      val expectedErrorPaths = neovim.spec.symlinks.zipWithIndex.collect:
        case (symlink, index) if symlink.privilege == SymlinkPrivilege.Sudo =>
          s"spec.plan[$neovimIndex].spec.symlinks[$index].sudo"
      val rejectedErrors = exampleWithSudoPolicy(allowSudoSymlinks = false) match
        case Left(ConfigLoadError.ValidationFailed(errors)) => errors
        case Left(error)    => abort(s"expected sudo policy validation errors, got $error")
        case Right(profile) => abort(s"expected sudo policy validation errors, got $profile")
      assert(rejectedErrors.map(_.path) == expectedErrorPaths)
      assert(rejectedErrors.forall(_.message.contains("neovim")))

    test("invalid manifest values aggregate validation errors with YAML paths"):
      val errors = validationErrors(invalidValuesYaml)

      assert(errors.exists(errorAt("apiVersion")))
      assert(errors.exists(errorAt("kind")))
      assert(errors.exists(errorAt("spec.plan[0].kind")))
      assert(errors.exists(errorAt("spec.plan[0].spec.download.archive.type")))
      assert(errors.exists(errorAt("spec.plan[0].spec.installer")))
      assert(errors.exists(errorAt("spec.plan[0].spec.executables[0].mode")))

    test("duplicate tool names report every duplicated name"):
      val errors   = validationErrors(duplicateNamesYaml)
      val messages = errors.map(_.message).mkString("\n")

      assert(messages.contains("duplicate tool name 'alpha'"))
      assert(messages.contains("duplicate tool name 'beta'"))

    test("validated profile construction rejects duplicate tool names"):
      val profile       = exampleProfile
      val duplicateSpec = profile.spec.copy(
        plan = profile.spec.plan :+ profile.spec.plan.head
      )
      val errors = profileValidationErrors(profile, duplicateSpec)

      assert(errors.exists(_.message.contains("duplicate tool name 'yazi'")))

    test("validated profile construction rejects unknown version references"):
      val profile            = exampleProfile
      val first              = profile.spec.plan.head
      val unknownVersionSpec = profile.spec.copy(
        plan = first.copy(spec = first.spec.copy(versionRef = "missing")) +:
          profile.spec.plan.tail
      )
      val errors = profileValidationErrors(profile, unknownVersionSpec)

      assert(errors.exists(errorAt("spec.plan[0].spec.versionRef")))

    test("validated profile construction rejects empty version references"):
      // The `validated` factory reaches the validator with no decode pass, so an empty ref must be
      // flagged here rather than assumed impossible after decoding.
      val profile      = exampleProfile
      val first        = profile.spec.plan.head
      val emptyRefSpec = profile.spec.copy(
        plan = first.copy(spec = first.spec.copy(versionRef = "")) +:
          profile.spec.plan.tail
      )
      val errors = profileValidationErrors(profile, emptyRefSpec)

      assert(errors.exists(errorAt("spec.plan[0].spec.versionRef")))

    test("an empty version ref reports once, from the decoder only"):
      val errors = validationErrors(
        policyModeYaml("").replace("        versionRef: alpha", "        versionRef: \"\"")
      )

      assert(errors == Vector(ValidationError(
        "spec.plan[0].spec.versionRef",
        "required string must not be empty"
      )))

    test("validated profile construction rejects forbidden sudo symlinks"):
      val profile           = exampleProfile
      val forbiddenSudoSpec = profile.spec.copy(
        policy = profile.spec.policy.copy(allowSudoSymlinks = PolicyOverride.Disabled)
      )
      val errors = profileValidationErrors(profile, forbiddenSudoSpec)

      assert(errors.exists(_.path.endsWith(".sudo")))

    test("tool names must be non-empty and path-safe"):
      val errors = validationErrors(unsafeToolNamesYaml)

      assert(errors.exists(errorAt("spec.plan[0].name")))
      assert(errors.exists(errorAt("spec.plan[1].name")))
      assert(errors.exists(errorAt("spec.plan[2].name")))
      assert(errors.exists(errorAt("spec.plan[3].name")))
      assert(errors.exists(_.message.contains("must not be empty")))
      assert(errors.exists(_.message.contains("traversal")))
      assert(errors.exists(_.message.contains("control")))
      assert(errors.exists(_.message.contains("path separators")))

    test("tool names that --only and --skip cannot address are rejected"):
      // A name starting with `-` reads as an option: `--only --only` and `--only=--only` both fail.
      Vector("my tool", "a,b", "non\u00a0breaking", "--only", "--skip", "-V").foreach: name =>
        val errors = validationErrors(
          policyModeYaml("").replace("    - name: alpha", s"    - name: \"$name\"")
        )

        assert(errors == Vector(ValidationError(
          "spec.plan[0].name",
          "tool name must not start with '-' or contain whitespace or commas, " +
            "so that --only and --skip can select it"
        )))

    test("a blank tool name produces only its prerequisite string error"):
      val errors = validationErrors(
        policyModeYaml("").replace("    - name: alpha", "    - name: \"\"")
      )

      assert(errors == Vector(ValidationError(
        "spec.plan[0].name",
        "required string must not be empty"
      )))

    test("distinct invalid tool names do not produce a sentinel duplicate"):
      val errors = validationErrors(unsafeToolNamesYaml)

      assert(!errors.exists(_.message.contains("duplicate tool name '<invalid>'")))

    test("the sentinel spelling remains a valid duplicate tool name"):
      val errors = validationErrors(
        duplicateNamesYaml.replace("    - name: alpha", "    - name: <invalid>")
      )

      assert(errors.exists(_.message.contains("duplicate tool name '<invalid>'")))

    test("unknown version refs report tool name and field path"):
      val errors = validationErrors(unknownVersionYaml)

      assert(
        errors.contains(
          ValidationError(
            "spec.plan[0].spec.versionRef",
            "tool 'alpha' references unknown version 'missing'"
          )
        )
      )

    test("sudo symlinks fail unless allowed by policy"):
      val rejected = validationErrors(sudoSymlinkYaml(allowSudoSymlinks = false))
      val accepted = ConfigModule.loadString(sudoSymlinkYaml(allowSudoSymlinks = true))

      assert(rejected.exists(errorAt("spec.plan[0].spec.symlinks[0].sudo")))
      assert(accepted.isRight)

    test("strict sudo symlink validation includes a typed policy suggestion"):
      val errors = validationErrors(strictSudoSymlinkYaml)

      assert(errors.exists(error =>
        error.path == "spec.plan[0].spec.symlinks[0].sudo" &&
          error.message.contains("strict-policy[sudo-symlink]") &&
          error.message.contains("suggestion[allow-sudo-symlinks]")
      ))

    test("a when selector no host can match is rejected"):
      val architecture = validationErrors(whenYaml("architecture: x86-64"))
      val family       = validationErrors(whenYaml("os:\n          family: linx"))

      assert(architecture == Vector(ValidationError(
        "spec.plan[0].when.architecture",
        "unsupported value 'x86-64', expected one of " +
          "amd64, x86_64, x64, arm64, aarch64, 386, x86, i386, i486, i586, i686"
      )))
      assert(family == Vector(ValidationError(
        "spec.plan[0].when.os.family",
        s"unsupported value 'linx', expected $osFamilySpellings"
      )))

    test("a when family naming more than one family, or a fragment of one, is rejected"):
      // Fragment matching resolved each of these to the first family in its table, so on any
      // other host the entry left every plan without a message.
      val values = Vector(
        "linux, darwin",
        "darwin or linux",
        "linux|darwin",
        "not-linux",
        "!windows",
        "Mac OS X"
      )
      values.foreach: value =>
        val errors = validationErrors(whenYaml(s"os:\n          family: \"$value\""))

        assert(errors == Vector(ValidationError(
          "spec.plan[0].when.os.family",
          s"unsupported value '$value', expected $osFamilySpellings"
        )))

    test("every when selector spelling the plan resolver understands still loads"):
      val families = Vector(
        "linux",
        "Linux",
        "\" linux \"",
        "darwin",
        "macos",
        "MacOS",
        "mac",
        "osx",
        "windows"
      )
      val architectures = Vector("amd64", "x86_64", "X64", "arm64", "aarch64", "\"386\"", "i686")
      val selectors     = families.map(family => s"os:\n          family: $family") ++
        architectures.map(architecture => s"architecture: $architecture")

      selectors.foreach: selector =>
        ConfigModule.loadString(whenYaml(selector)) match
          case Right(_)    => ()
          case Left(error) => abort(s"expected '$selector' to load, got $error")

    test("host vocabulary maps a spelling to its canonical name and knows no other"):
      assert(HostVocabulary.osFamily(" LINUX ").contains("linux"))
      assert(HostVocabulary.osFamily("osx").contains("darwin"))
      assert(HostVocabulary.osFamily("Mac OS X").isEmpty)
      assert(HostVocabulary.osFamily("FreeBSD").isEmpty)
      assert(HostVocabulary.hostOsFamily("Mac OS X").contains("darwin"))
      assert(HostVocabulary.hostOsFamily("Windows 11").contains("windows"))
      assert(HostVocabulary.hostOsFamily("FreeBSD").isEmpty)
      // The plan resolver normalizes a manifest family with the host rule, so each manifest
      // spelling must mean the same family under both.
      Vector("linux", "darwin", "macos", "mac", "osx", "windows").foreach: spelling =>
        assert(HostVocabulary.hostOsFamily(spelling) == HostVocabulary.osFamily(spelling))
      assert(HostVocabulary.architecture("x86_64").contains("amd64"))
      assert(HostVocabulary.architecture("AArch64").contains("arm64"))
      assert(HostVocabulary.architecture("i686").contains("386"))
      assert(HostVocabulary.architecture("riscv64").isEmpty)

    test("validated profile construction rejects a when selector no host can match"):
      val profile  = exampleProfile
      val first    = profile.spec.plan.head
      val typoSpec = profile.spec.copy(
        plan = first.copy(when = Some(WhenClause(None, Some("amd46")))) +: profile.spec.plan.tail
      )
      val errors = profileValidationErrors(profile, typoSpec)

      assert(errors.exists(errorAt("spec.plan[0].when.architecture")))

    test("installer blocks are rejected"):
      val errors = validationErrors(unsupportedInstallerYaml)

      assert(errors.contains(
        ValidationError(
          "spec.plan[0].spec.installer",
          "installer scripts are not supported; use direct binary or archive download"
        )
      ))

    test("sha256 checksums must be 64 hex characters"):
      val errors = validationErrors(invalidChecksumYaml)

      assert(errors.contains(
        ValidationError(
          "spec.plan[0].spec.download.checksum.value",
          "sha256 checksum must be 64 hexadecimal characters"
        )
      ))

    test("checksum discovery source is typed and mutually exclusive with literal value"):
      val profile = ConfigModule.loadString(checksumDiscoveryYaml) match
        case Right(value) => value
        case Left(error)  => abort(s"expected checksum discovery config, got $error")
      val checksum = checksumFor(profile, "alpha").getOrElse(abort("missing checksum"))

      assert(checksum.algorithm == ChecksumAlgorithm.Sha256)
      assert(checksum.value.isEmpty)
      assert(checksum.discover.contains(ChecksumDiscoverySpec(
        ChecksumDiscoveryKind.Sha256Sum,
        "https://example.invalid/releases/${version}/SHA256SUMS",
        Some("alpha-${version}.tar.gz")
      )))

    test("checksum discovery rejects ambiguous checksum declarations"):
      val errors = validationErrors(ambiguousChecksumYaml)

      assert(errors.contains(ValidationError(
        "spec.plan[0].spec.download.checksum",
        "checksum must declare either value or discover, not both"
      )))

    test("a non-string checksum value reports only the type error"):
      val errors = validationErrors(invalidChecksumYaml.replace("value: not-a-sha", "value: 123"))

      assert(errors == Vector(ValidationError(
        "spec.plan[0].spec.download.checksum.value",
        "value must be a string"
      )))

    test("a non-string checksum value beside discover is still an ambiguous declaration"):
      val errors = validationErrors(ambiguousChecksumYaml.replace("a" * 64, "123"))

      assert(errors == Vector(
        ValidationError("spec.plan[0].spec.download.checksum.value", "value must be a string"),
        ValidationError(
          "spec.plan[0].spec.download.checksum",
          "checksum must declare either value or discover, not both"
        )
      ))

    test("an empty document reports one error, not one per field it lacks"):
      assert(validationErrors("") == Vector(ValidationError("$", "value must be a map")))

    test("a missing block reports one error, not one per field under it"):
      val errors =
        validationErrors(policyModeYaml("").replace("metadata:\n  name: policy-mode\n", ""))

      assert(errors == Vector(ValidationError("metadata", "required map is missing")))

    test("plan entries that are not maps report one error each"):
      val yaml   = policyModeYaml("")
      val errors = validationErrors(
        yaml.substring(0, yaml.indexOf("  plan:")) + "  plan:\n    - first\n    - second\n"
      )

      assert(errors == Vector(
        ValidationError("spec.plan[0]", "value must be a map"),
        ValidationError("spec.plan[1]", "value must be a map")
      ))

    test("a list item that is not a map reports one error"):
      val errors = validationErrors(
        policyModeYaml("").replace("          - path: bin/alpha", "          - bin/alpha")
      )

      assert(errors == Vector(ValidationError(
        "spec.plan[0].spec.executables[0]",
        "value must be a map"
      )))

    test("a wrong-typed discover block reports one error"):
      val errors = validationErrors(
        invalidChecksumYaml.replace("value: not-a-sha", "discover: sha256sum")
      )

      assert(errors == Vector(ValidationError(
        "spec.plan[0].spec.download.checksum.discover",
        "value must be a map"
      )))

    test("a wrong-typed versions block does not also report unknown version refs"):
      val errors = validationErrors(
        policyModeYaml("").replace("  versions:\n    alpha: \"1.0.0\"", "  versions: []")
      )

      assert(errors == Vector(ValidationError("spec.versions", "value must be a map")))

    test("a wrong-typed allowSudoSymlinks does not also forbid the sudo symlinks"):
      val errors = validationErrors(
        sudoSymlinkYaml(allowSudoSymlinks = true)
          .replace("allowSudoSymlinks: true", "allowSudoSymlinks: \"true\"")
      )

      assert(errors == Vector(ValidationError(
        "spec.policy.allowSudoSymlinks",
        "value must be a boolean"
      )))

    test("a wrong-typed policy block does not also forbid the sudo symlinks"):
      val errors = validationErrors(
        sudoSymlinkYaml(allowSudoSymlinks = true).replace(
          "  policy:\n    appsDir: \"${HOME}/.apps\"\n    allowSudoSymlinks: true",
          "  policy: []"
        )
      )

      assert(errors == Vector(ValidationError("spec.policy", "value must be a map")))

    test("unknown fields are rejected at every manifest level"):
      val yaml = policyModeYaml("")
        .replace("metadata:\n", "metadata:\n  unexpectedMetadata: value\n")
        .replace("    appsDir:", "    cleanInstall: true\n    appsDir:")
        .replace(
          "          filename: alpha",
          "          filename: alpha\n          unexpectedDownload: value"
        )
      val errors = validationErrors(yaml)

      assert(errors.contains(ValidationError(
        "metadata.unexpectedMetadata",
        "unknown field 'unexpectedMetadata'"
      )))
      assert(errors.contains(ValidationError(
        "spec.policy.cleanInstall",
        "unknown field 'cleanInstall'"
      )))
      assert(errors.contains(ValidationError(
        "spec.plan[0].spec.download.unexpectedDownload",
        "unknown field 'unexpectedDownload'"
      )))

    test("an unknown key spelled like a manifest path does not hide the validator's errors"):
      val broken = whenYaml("architecture: sparc")
        .replace("  name: policy-mode", "  name: a/b")
        .replace("versionRef: alpha", "versionRef: missing")
      val rootKey = validationErrors("\"$\": typo\n" + broken)
      val specKey =
        validationErrors(broken.replace("  vars: {}", "  vars: {}\n  \"plan[0]\": typo"))

      assert(rootKey.contains(ValidationError("$", "unknown field '$'")))
      assert(specKey.contains(ValidationError("spec.plan[0]", "unknown field 'plan[0]'")))
      Vector(rootKey, specKey).foreach: errors =>
        assert(errors.exists(errorAt("metadata.name")))
        assert(errors.exists(errorAt("spec.plan[0].when.architecture")))
        assert(errors.exists(errorAt("spec.plan[0].spec.versionRef")))

    test("YAML mappings reject non-string keys instead of silently dropping them"):
      ConfigModule.loadString("1: value") match
        case Left(ConfigLoadError.ParseFailed(message)) =>
          assert(message.contains("mapping keys must be strings"))
        case result => abort(s"expected a parse failure, got $result")

    test("YAML input is bounded before constructing an unbounded object graph"):
      val oversized = "x" * (2 * 1024 * 1024 + 1)
      ConfigModule.loadString(oversized) match
        case Left(ConfigLoadError.ParseFailed(message)) =>
          assert(message.contains("exceeds the limit"))
        case result => abort(s"expected a parse failure, got $result")

    test("text past the code-point limit is rejected before the YAML parser scans it"):
      // snakeyaml checks its own limit only when it fetches a token, so it scanned this one
      // comment in full, which is quadratic, and then accepted it.
      val comment = "#" * (8 * 1024 * 1024)

      assert(parseFailure(comment) == "YAML document exceeds the limit of 2097152 code points")

    test("a line past the line limit is rejected with its line number"):
      // One long number scalar took the parser tens of seconds while inside every other limit.
      val hexDigits     = "a: 0x" + ("f" * 2000000)
      val decimalDigits = "a: " + ("1" * 2000000)

      Vector(hexDigits, decimalDigits).foreach: yaml =>
        assert(parseFailure(yaml) == "YAML line 1 exceeds the limit of 65536 characters")
      assert(
        parseFailure("a: 1\nb: " + ("x" * 65536)) ==
          "YAML line 2 exceeds the limit of 65536 characters"
      )

    test("invalid filesystem path strings return a parse failure"):
      ConfigModule.load("config\u0000.yaml") match
        case Left(ConfigLoadError.ParseFailed(message)) =>
          assert(message.startsWith("invalid config path:"))
        case result => abort(s"expected an invalid-path parse failure, got $result")

    test("a missing config file is reported with a reason, not with its path repeated"):
      val missing = java.nio.file.Path.of("no-such-directory", "config.yaml").toAbsolutePath

      ConfigLoader.load(missing) match
        case Left(ConfigLoadError.ReadFailed(path, message)) =>
          assert(path == missing)
          assert(message == "no such file")
        case result => abort(s"expected a read failure, got $result")

    test("an oversized config file is a read failure, not an OutOfMemoryError"):
      // One byte over the limit takes the same branch as a disk image: past 2 GiB the JDK cannot
      // read a file into one array at any heap size, and the loader reads at most limit + 1 bytes.
      val directory = Files.createTempDirectory("binstaller-oversized")
      val oversized = directory.resolve("oversized.yaml")
      try
        val _ = Files.write(oversized, Array.fill(8 * 1024 * 1024 + 1)('#'.toByte))

        ConfigModule.load(oversized) match
          case Left(ConfigLoadError.ReadFailed(path, message)) =>
            assert(path == oversized)
            assert(message == "config file is larger than 8388608 bytes")
          case result => abort(s"expected a read failure, got $result")
      finally
        val _ = Files.deleteIfExists(oversized)
        Files.delete(directory)

    test("a config file that is not UTF-8 text is a read failure"):
      val binary = Files.createTempFile("binstaller-binary", ".yaml")
      try
        Files.write(binary, Array(0xff.toByte, 0xfe.toByte, 0x00.toByte))

        ConfigModule.load(binary) match
          case Left(ConfigLoadError.ReadFailed(_, message)) =>
            assert(message == "file is not valid UTF-8 text")
          case result => abort(s"expected a read failure, got $result")
      finally Files.delete(binary)

    test("deeply nested YAML fails as a parse error instead of overflowing the stack"):
      // One bracket per line keeps every line inside the line limit, so the parser sees it.
      val depth  = 50000
      val nested = ("[\n" * depth) + ("]\n" * depth)
      ConfigModule.loadString(nested) match
        case Left(ConfigLoadError.ParseFailed(message)) =>
          assert(message.contains("too deeply nested"))
        case result => abort(s"expected a parse failure, got $result")

    test("nesting past the supported depth fails at the depth guard"):
      val depth = 150
      ConfigModule.loadString(("[" * depth) + ("]" * depth)) match
        case Left(ConfigLoadError.ParseFailed(message)) =>
          assert(message.contains("maximum supported depth"))
        case result => abort(s"expected a parse failure, got $result")

    test("a recursive alias fails at the depth guard instead of looping"):
      ConfigModule.loadString("a: &a [*a]") match
        case Left(ConfigLoadError.ParseFailed(message)) =>
          assert(message.contains("maximum supported depth"))
        case result => abort(s"expected a parse failure, got $result")

    test("a duplicate key is a parse failure, not a silent last-one-wins"):
      ConfigModule.loadString("a: 1\na: 2") match
        case Left(ConfigLoadError.ParseFailed(message)) => assert(message.contains("duplicate key"))
        case result => abort(s"expected a parse failure, got $result")

    test("a multi-document stream is a parse failure, not a silently ignored tail"):
      ConfigModule.loadString("a: 1\n---\nb: 2") match
        case Left(ConfigLoadError.ParseFailed(message)) =>
          assert(message.contains("single document"))
        case result => abort(s"expected a parse failure, got $result")

    test("a block shared through an anchor and an alias loads as both fields"):
      val yaml = policyModeYaml("").replace(
        "  name: policy-mode",
        "  name: policy-mode\n  labels: &shared\n    role: development\n  annotations: *shared"
      )
      val metadata = ConfigModule.loadString(yaml) match
        case Right(profile) => profile.metadata
        case Left(error)    => abort(s"expected a valid profile, got $error")

      assert(metadata.labels == Map("role" -> "development"))
      assert(metadata.annotations == metadata.labels)

    test("a self-merging document fails at the depth guard instead of looping"):
      // With merge keys on, snakeyaml looped forever inside its own merge before any guard here.
      Vector("a: &a {<<: *a}", "a: &a {k: 1, <<: *a}", "a: &a\n  <<: *a").foreach: yaml =>
        withinSeconds(30, () => ConfigModule.loadString(yaml)) match
          case Left(ConfigLoadError.ParseFailed(message)) =>
            assert(message.contains("maximum supported depth"))
          case result => abort(s"expected a parse failure for '$yaml', got $result")

    test("an explicit merge tag is rejected before the YAML parser merges anything"):
      // The composer merges on the tag itself, whatever the scalar resolver says about `<<`.
      val tagged = Vector(
        "a: &a {!!merge <<: *a}",
        "a: &a {!<tag:yaml.org,2002:merge> x: *a}",
        "%TAG !t! tag:yaml.org,2002:\n---\na: &a {!t!merge x: *a}"
      )
      tagged.foreach: yaml =>
        withinSeconds(30, () => ConfigModule.loadString(yaml)) match
          case Left(ConfigLoadError.ParseFailed(message)) =>
            assert(message == "YAML merge keys are not supported")
          case result => abort(s"expected a parse failure for '$yaml', got $result")

    test("a wide map merged many times is not expanded by the YAML parser"):
      // A merge copies its source inside snakeyaml, before the node budget counts anything: 49
      // merges of one wide map, inside the alias cap, cost gigabytes of heap.
      val wide    = (0 until 2000).map(index => s"k$index: v").mkString("{", ", ", "}")
      val sources = Vector.fill(49)("*wide").mkString("[", ", ", "]")
      val yaml    = policyModeYaml("").replace(
        "  name: policy-mode",
        s"  name: policy-mode\n  annotations: &wide $wide\n  labels: {<<: $sources}"
      )

      assert(validationErrors(yaml) == Vector(ValidationError(
        "metadata.labels.<<",
        "value must be a string"
      )))

    test("a merge-key spelling in spec.vars is an ordinary variable name"):
      val yaml = policyModeYaml("").replace("  vars: {}", "  vars:\n    <<: value")
      val vars = ConfigModule.loadString(yaml) match
        case Right(profile) => profile.spec.vars
        case Left(error)    => abort(s"expected a valid profile, got $error")

      assert(vars == Map("<<" -> "value"))

    test("alias fan-out that expands past the node budget fails as a parse error"):
      // 33 alias references, inside snakeyaml's cap of 50, expand to several million nodes.
      val base   = "l0: &l0 [x, x, x, x, x, x, x, x, x, x]"
      val levels = (1 to 11).map: level =>
        val previous = s"*l${level - 1}"
        s"l$level: &l$level [$previous, $previous, $previous]"
      ConfigModule.loadString((base +: levels).mkString("\n")) match
        case Left(ConfigLoadError.ParseFailed(message)) => assert(message.contains("aliases"))
        case Left(other) => abort(s"expected a parse failure, got ${other.getClass.getSimpleName}")
        case Right(_)    => abort("expected a parse failure, got a profile")

    test("every YAML 1.2 Core spelling of null is null, not a literal string"):
      Vector("~", "null", "Null", "NULL", "").foreach: spelling =>
        val errors = validationErrors(policyModeYaml(s"stateFile: $spelling"))

        assert(errors == Vector(ValidationError(
          "spec.policy.stateFile",
          "value must be a string"
        )))

    test("capitalized booleans are booleans"):
      val policy = ConfigModule.loadString(policyModeYaml("continueOnError: True")) match
        case Right(profile) => profile.spec.policy
        case Left(error)    => abort(s"expected a valid profile, got $error")

      assert(policy.continueOnError == PolicyOverride.Enabled)

    test("YAML 1.1 booleans such as yes stay strings"):
      val errors = validationErrors(policyModeYaml("continueOnError: yes"))

      assert(errors == Vector(ValidationError(
        "spec.policy.continueOnError",
        "value must be a boolean"
      )))

    test("an unquoted executable mode is a number and must be quoted"):
      val errors = validationErrors(policyModeYaml("").replace(
        "          - path: bin/alpha",
        "          - path: bin/alpha\n            mode: 0755"
      ))

      assert(errors == Vector(ValidationError(
        "spec.plan[0].spec.executables[0].mode",
        "mode must be a quoted string, e.g. \"0755\""
      )))

    test("required strings reject empty values"):
      val errors = validationErrors(emptyUrlYaml)

      assert(errors.exists(errorAt("spec.plan[0].spec.download.url")))
      assert(errors.exists(_.message.contains("must not be empty")))

    test("numeric version scalars are rejected with a quote-the-version message"):
      val errors = validationErrors(numericVersionYaml)

      assert(errors.exists(errorAt("spec.versions.alpha")))
      assert(errors.exists(_.message.contains("quoted string")))

    test("metadata name is hardened like plan tool names"):
      val errors = validationErrors(unsafeMetadataNameYaml)

      assert(errors.exists(errorAt("metadata.name")))
      assert(errors.exists(_.message.contains("path separators")))

    test("an unsafe metadata name is reported as a profile name, not a tool name"):
      val errors = validationErrors(unsafeMetadataNameYaml)

      assert(errors == Vector(ValidationError(
        "metadata.name",
        "profile name must not contain path separators"
      )))

    test("a profile name may contain the spaces and commas a tool name may not"):
      val yaml = policyModeYaml("").replace("  name: policy-mode", "  name: \"dev tools, laptop\"")

      assert(ConfigModule.loadString(yaml).map(_.metadata.name) == Right("dev tools, laptop"))

    test("error accumulation surfaces every failing sibling field, not just the first"):
      val errors = validationErrors(twoEmptySiblingFieldsYaml)

      // url and filename are decoded as siblings in the same accumulate body; both must surface.
      assert(errors.exists(errorAt("spec.plan[0].spec.download.url")))
      assert(errors.exists(errorAt("spec.plan[0].spec.download.filename")))
      assert(errors.count(_.message.contains("must not be empty")) >= 2)

  private def validationErrors(yaml: String): Vector[ValidationError] =
    ConfigModule.loadString(yaml) match
      case Left(ConfigLoadError.ValidationFailed(errors)) => errors
      case Left(error)    => abort(s"expected validation errors, got $error")
      case Right(profile) => abort(s"expected validation errors, got $profile")

  private def profileValidationErrors(
      profile: BinaryDistributionProfile,
      spec: ProfileSpec
  ): Vector[ValidationError] = BinaryDistributionProfile.validated(
    profile.apiVersion,
    profile.kind,
    profile.metadata,
    spec
  ) match
    case Left(errors)     => errors
    case Right(validated) => abort(s"expected profile validation errors, got $validated")

  private def exampleProfile: BinaryDistributionProfile = ConfigModule.load(exampleConfigPath) match
    case Right(profile) => profile
    case Left(error)    => abort(s"expected valid example config, got $error")

  private def pinnedVersions(profile: BinaryDistributionProfile): Vector[(String, String)] =
    exampleToolNames.flatMap: name =>
      profile.spec.versions.get(name).collect:
        case VersionSource.Pinned(value) => name -> value

  private def dynamicLatestUrlNames(profile: BinaryDistributionProfile): Vector[String] =
    exampleToolNames.filter: name =>
      profile.spec.versions.get(name).exists:
        case VersionSource.Dynamic(DynamicVersionKind.LatestUrl, _) => true
        case _                                                      => false

  private def checksumFor(
      profile: BinaryDistributionProfile,
      toolName: String
  ): Option[ChecksumSpec] = toolNamed(profile, toolName).spec.download.checksum

  private def toolNamed(profile: BinaryDistributionProfile, name: String): PlanEntry =
    profile.spec.plan.find(_.name.value == name).getOrElse(abort(s"missing tool $name"))

  private def exampleWithSudoPolicy(
      allowSudoSymlinks: Boolean
  ): Either[ConfigLoadError, BinaryDistributionProfile] =
    val source = Files.readString(exampleConfigPath)
    // Silent false-pass guard: if the example config drifts, the replace below is a no-op.
    assert(source.contains("allowSudoSymlinks: true"))
    ConfigModule.loadString(source.replace(
      "allowSudoSymlinks: true",
      s"allowSudoSymlinks: $allowSudoSymlinks"
    ))

  private def errorAt(path: String)(error: ValidationError): Boolean = error.path == path

  private def parseFailure(yaml: String): String = ConfigModule.loadString(yaml) match
    case Left(ConfigLoadError.ParseFailed(message)) => message
    case result => abort(s"expected a parse failure, got $result")

  // A hang must fail its test instead of stalling the suite. The global pool's threads are
  // daemons, so a thread stuck in a loop does not keep the test JVM alive.
  private def withinSeconds[A](seconds: Int, body: () => A): A =
    Await.result(Future(body())(using ExecutionContext.global), seconds.seconds)

  private val osFamilySpellings = "one of linux, darwin, macos, mac, osx, windows"

  private def abort(message: String): Nothing = throw java.lang.AssertionError(message)

  /** Parse a hex literal into a digest, failing the test rather than the assertion on a typo. */
  private def digest(hex: String): Sha256Digest = Sha256Digest.fromString(hex) match
    case Right(value) => value
    case Left(error)  => abort(s"invalid test digest: $error")

  private def exampleConfigPath: Path = repoRootCandidates
    .map(_.resolve("config.example.yaml"))
    .find(Files.exists(_))
    .getOrElse(abort("could not locate config.example.yaml"))

  private def repoRootCandidates: Iterator[Path] =
    sys.props.get("binstaller.repoRoot").iterator.map(Path.of(_).toAbsolutePath) ++
      upwardPaths(Path.of("").toAbsolutePath)

  private def upwardPaths(start: Path): Iterator[Path] =
    Iterator.iterate(start)(_.getParent).takeWhile(_ != null)

  private val exampleToolNames: Vector[String] = Vector(
    "yazi",
    "zig",
    "minikube",
    "xplr",
    "kind",
    "zellij",
    "helm",
    "kubectl",
    "kustomize",
    "neovide",
    "neovim",
    "lazygit",
    "jujutsu",
    "dotbot",
    "nerd-font-installer"
  )

  private val expectedPinnedVersions: Vector[(String, String)] = Vector(
    "yazi"                -> "v26.5.6",
    "zig"                 -> "0.15.2",
    "kind"                -> "0.31.0",
    "zellij"              -> "0.44.1",
    "helm"                -> "v3.21.2",
    "kustomize"           -> "v5.8.1",
    "lazygit"             -> "0.61.0",
    "jujutsu"             -> "0.40.0",
    "dotbot"              -> "v0.3.0",
    "nerd-font-installer" -> "v1.0.6"
  )

  private val expectedDynamicLatestUrlNames: Vector[String] = Vector(
    "minikube",
    "xplr",
    "neovide",
    "neovim"
  )

  private val invalidValuesYaml: String = """
                                            |apiVersion: example.invalid/v1
                                            |kind: WrongKind
                                            |metadata:
                                            |  name: invalid-values
                                            |spec:
                                            |  policy:
                                            |    appsDir: "${HOME}/.apps"
                                            |    allowSudoSymlinks: true
                                            |  vars: {}
                                            |  versions:
                                            |    alpha: "1.0.0"
                                            |  plan:
                                            |    - name: alpha
                                            |      kind: legacy-script
                                            |      spec:
                                            |        versionRef: alpha
                                            |        installDir: "${appsDir}/alpha"
                                            |        download:
                                            |          url: https://example.invalid/alpha.tar.rar
                                            |          filename: alpha.tar.rar
                                            |          archive:
                                            |            type: rar
                                            |            extract:
                                            |              files:
                                            |                - from: alpha
                                            |                  to: bin/alpha
                                            |        installer:
                                            |          shell: fish
                                            |          args: []
                                            |        executables:
                                            |          - path: bin/alpha
                                            |            mode: "755"
                                            |""".stripMargin

  private val invalidChecksumYaml: String = """
                                              |apiVersion: binstaller.io/v1alpha1
                                              |kind: BinaryDistributionProfile
                                              |metadata:
                                              |  name: invalid-checksum
                                              |spec:
                                              |  policy:
                                              |    appsDir: "${HOME}/.apps"
                                              |  vars: {}
                                              |  versions:
                                              |    alpha: "1.0.0"
                                              |  plan:
                                              |    - name: alpha
                                              |      kind: binary-tool
                                              |      spec:
                                              |        versionRef: alpha
                                              |        installDir: "${appsDir}/alpha"
                                              |        download:
                                              |          url: https://example.invalid/alpha
                                              |          filename: alpha
                                              |          checksum:
                                              |            algorithm: sha256
                                              |            value: not-a-sha
                                              |        executables:
                                              |          - path: bin/alpha
                                              |""".stripMargin

  private val checksumDiscoveryYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: checksum-discovery
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |  vars: {}
      |  versions:
      |    alpha: "1.0.0"
      |  plan:
      |    - name: alpha
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${appsDir}/alpha"
      |        download:
      |          url: https://example.invalid/releases/${version}/alpha-${version}.tar.gz
      |          filename: alpha-${version}.tar.gz
      |          checksum:
      |            algorithm: sha256
      |            discover:
      |              type: sha256sum
      |              url: https://example.invalid/releases/${version}/SHA256SUMS
      |              file: alpha-${version}.tar.gz
      |        executables:
      |          - path: bin/alpha
      |""".stripMargin

  private val ambiguousChecksumYaml: String =
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: ambiguous-checksum
       |spec:
       |  policy:
       |    appsDir: "$${HOME}/.apps"
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$${appsDir}/alpha"
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |          checksum:
       |            algorithm: sha256
       |            value: ${"a" * 64}
       |            discover:
       |              type: sha256sum
       |              url: https://example.invalid/SHA256SUMS
       |        executables:
       |          - path: bin/alpha
       |""".stripMargin

  private val duplicateNamesYaml: String = """
                                             |apiVersion: binstaller.io/v1alpha1
                                             |kind: BinaryDistributionProfile
                                             |metadata:
                                             |  name: duplicate-names
                                             |spec:
                                             |  policy:
                                             |    appsDir: "${HOME}/.apps"
                                             |  vars: {}
                                             |  versions:
                                             |    alpha: "1.0.0"
                                             |    beta: "2.0.0"
                                             |  plan:
                                             |    - name: alpha
                                             |      kind: binary-tool
                                             |      spec:
                                             |        versionRef: alpha
                                             |        installDir: "${appsDir}/alpha"
                                             |        download:
                                             |          url: https://example.invalid/alpha
                                             |          filename: alpha
                                             |        executables:
                                             |          - path: bin/alpha
                                             |    - name: beta
                                             |      kind: binary-tool
                                             |      spec:
                                             |        versionRef: beta
                                             |        installDir: "${appsDir}/beta"
                                             |        download:
                                             |          url: https://example.invalid/beta
                                             |          filename: beta
                                             |        executables:
                                             |          - path: bin/beta
                                             |    - name: alpha
                                             |      kind: binary-tool
                                             |      spec:
                                             |        versionRef: alpha
                                             |        installDir: "${appsDir}/alpha-again"
                                             |        download:
                                             |          url: https://example.invalid/alpha-again
                                             |          filename: alpha-again
                                             |        executables:
                                             |          - path: bin/alpha
                                             |    - name: beta
                                             |      kind: binary-tool
                                             |      spec:
                                             |        versionRef: beta
                                             |        installDir: "${appsDir}/beta-again"
                                             |        download:
                                             |          url: https://example.invalid/beta-again
                                             |          filename: beta-again
                                             |        executables:
                                             |          - path: bin/beta
                                             |""".stripMargin

  private val unknownVersionYaml: String = """
                                             |apiVersion: binstaller.io/v1alpha1
                                             |kind: BinaryDistributionProfile
                                             |metadata:
                                             |  name: unknown-version
                                             |spec:
                                             |  policy:
                                             |    appsDir: "${HOME}/.apps"
                                             |  vars: {}
                                             |  versions:
                                             |    known: "1.0.0"
                                             |  plan:
                                             |    - name: alpha
                                             |      kind: binary-tool
                                             |      spec:
                                             |        versionRef: missing
                                             |        installDir: "${appsDir}/alpha"
                                             |        download:
                                             |          url: https://example.invalid/alpha
                                             |          filename: alpha
                                             |        executables:
                                             |          - path: bin/alpha
                                             |""".stripMargin

  private val unsafeToolNamesYaml: String = """
                                              |apiVersion: binstaller.io/v1alpha1
                                              |kind: BinaryDistributionProfile
                                              |metadata:
                                              |  name: unsafe-tool-names
                                              |spec:
                                              |  policy:
                                              |    appsDir: "${HOME}/.apps"
                                              |  vars: {}
                                              |  versions:
                                              |    alpha: "1.0.0"
                                              |  plan:
                                              |    - name: ""
                                              |      kind: binary-tool
                                              |      spec:
                                              |        versionRef: alpha
                                              |        installDir: "${appsDir}/empty"
                                              |        download:
                                              |          url: https://example.invalid/empty
                                              |          filename: empty
                                              |        executables:
                                              |          - path: bin/empty
                                              |    - name: ".."
                                              |      kind: binary-tool
                                              |      spec:
                                              |        versionRef: alpha
                                              |        installDir: "${appsDir}/traversal"
                                              |        download:
                                              |          url: https://example.invalid/traversal
                                              |          filename: traversal
                                              |        executables:
                                              |          - path: bin/traversal
                                              |    - name: "bad\a"
                                              |      kind: binary-tool
                                              |      spec:
                                              |        versionRef: alpha
                                              |        installDir: "${appsDir}/control"
                                              |        download:
                                              |          url: https://example.invalid/control
                                              |          filename: control
                                              |        executables:
                                              |          - path: bin/control
                                              |    - name: alpha/beta
                                              |      kind: binary-tool
                                              |      spec:
                                              |        versionRef: alpha
                                              |        installDir: "${appsDir}/separator"
                                              |        download:
                                              |          url: https://example.invalid/separator
                                              |          filename: separator
                                              |        executables:
                                              |          - path: bin/separator
                                              |""".stripMargin

  private val unsupportedInstallerYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: unsupported-installer
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |  vars: {}
      |  versions:
      |    alpha: "1.0.0"
      |  plan:
      |    - name: alpha
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${appsDir}/alpha"
      |        download:
      |          url: https://example.invalid/install.sh
      |          filename: install.sh
      |        installer:
      |          shell: sh
      |          args:
      |            - "${downloadPath}"
      |        executables:
      |          - path: bin/alpha
      |""".stripMargin

  private val emptyUrlYaml: String = """
                                       |apiVersion: binstaller.io/v1alpha1
                                       |kind: BinaryDistributionProfile
                                       |metadata:
                                       |  name: empty-url
                                       |spec:
                                       |  policy:
                                       |    appsDir: "${HOME}/.apps"
                                       |  vars: {}
                                       |  versions:
                                       |    alpha: "1.0.0"
                                       |  plan:
                                       |    - name: alpha
                                       |      kind: binary-tool
                                       |      spec:
                                       |        versionRef: alpha
                                       |        installDir: "${appsDir}/alpha"
                                       |        download:
                                       |          url: ""
                                       |          filename: alpha
                                       |        executables:
                                       |          - path: bin/alpha
                                       |""".stripMargin

  private val numericVersionYaml: String = """
                                             |apiVersion: binstaller.io/v1alpha1
                                             |kind: BinaryDistributionProfile
                                             |metadata:
                                             |  name: numeric-version
                                             |spec:
                                             |  policy:
                                             |    appsDir: "${HOME}/.apps"
                                             |  vars: {}
                                             |  versions:
                                             |    alpha: 1.20
                                             |  plan:
                                             |    - name: alpha
                                             |      kind: binary-tool
                                             |      spec:
                                             |        versionRef: alpha
                                             |        installDir: "${appsDir}/alpha"
                                             |        download:
                                             |          url: https://example.invalid/alpha
                                             |          filename: alpha
                                             |        executables:
                                             |          - path: bin/alpha
                                             |""".stripMargin

  private val unsafeMetadataNameYaml: String = """
                                                 |apiVersion: binstaller.io/v1alpha1
                                                 |kind: BinaryDistributionProfile
                                                 |metadata:
                                                 |  name: "../escape"
                                                 |spec:
                                                 |  policy:
                                                 |    appsDir: "${HOME}/.apps"
                                                 |  vars: {}
                                                 |  versions:
                                                 |    alpha: "1.0.0"
                                                 |  plan:
                                                 |    - name: alpha
                                                 |      kind: binary-tool
                                                 |      spec:
                                                 |        versionRef: alpha
                                                 |        installDir: "${appsDir}/alpha"
                                                 |        download:
                                                 |          url: https://example.invalid/alpha
                                                 |          filename: alpha
                                                 |        executables:
                                                 |          - path: bin/alpha
                                                 |""".stripMargin

  private val twoEmptySiblingFieldsYaml: String = """
                                                    |apiVersion: binstaller.io/v1alpha1
                                                    |kind: BinaryDistributionProfile
                                                    |metadata:
                                                    |  name: two-empty-siblings
                                                    |spec:
                                                    |  policy:
                                                    |    appsDir: "${HOME}/.apps"
                                                    |  vars: {}
                                                    |  versions:
                                                    |    alpha: "1.0.0"
                                                    |  plan:
                                                    |    - name: alpha
                                                    |      kind: binary-tool
                                                    |      spec:
                                                    |        versionRef: alpha
                                                    |        installDir: "${appsDir}/alpha"
                                                    |        download:
                                                    |          url: ""
                                                    |          filename: ""
                                                    |        executables:
                                                    |          - path: bin/alpha
                                                    |""".stripMargin

  private def policyModeYaml(policyLines: String): String =
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: policy-mode
       |spec:
       |  policy:
       |    appsDir: "$${HOME}/.apps"
       |    $policyLines
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$${appsDir}/alpha"
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |""".stripMargin

  private def whenYaml(selector: String): String = policyModeYaml("").replace(
    "      kind: binary-tool",
    s"      kind: binary-tool\n      when:\n        $selector"
  )

  private def sudoSymlinkYaml(allowSudoSymlinks: Boolean): String =
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: sudo-symlink
       |spec:
       |  policy:
       |    appsDir: "$${HOME}/.apps"
       |    allowSudoSymlinks: $allowSudoSymlinks
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$${appsDir}/alpha"
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |        symlinks:
       |          - path: /usr/local/bin/alpha
       |            target: "$${appsDir}/alpha/bin/alpha"
       |            sudo: true
       |""".stripMargin

  private val strictSudoSymlinkYaml: String = """
                                                |apiVersion: binstaller.io/v1alpha1
                                                |kind: BinaryDistributionProfile
                                                |metadata:
                                                |  name: strict-sudo-symlink
                                                |spec:
                                                |  policy:
                                                |    mode: strict
                                                |    appsDir: "${HOME}/.apps"
                                                |  vars: {}
                                                |  versions:
                                                |    alpha: "1.0.0"
                                                |  plan:
                                                |    - name: alpha
                                                |      kind: binary-tool
                                                |      spec:
                                                |        versionRef: alpha
                                                |        installDir: "${appsDir}/alpha"
                                                |        download:
                                                |          url: https://example.invalid/alpha
                                                |          filename: alpha
                                                |        executables:
                                                |          - path: bin/alpha
                                                |        symlinks:
                                                |          - path: /usr/local/bin/alpha
                                                |            target: "${appsDir}/alpha/bin/alpha"
                                                |            sudo: true
                                                |""".stripMargin
