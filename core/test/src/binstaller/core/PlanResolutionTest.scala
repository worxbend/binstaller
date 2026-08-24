package binstaller.core

import binstaller.config.ConfigModule
import binstaller.config.ArchiveType
import utest.*

import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** Turning a manifest into a `ResolvedPlan`: variables, versions, policy and path validation. */
object PlanResolutionTest extends TestSuite with CoreTestSupport:

  val tests: Tests = Tests:
    test("pinned versions interpolate into URLs and paths"):
      val plan = resolve(validPinnedYaml)

      val tool = onlyTool(plan)
      assert(tool.installDir == "/home/test/.apps/alpha-1.2.3")
      assert(tool.download.url == "https://example.invalid/alpha-1.2.3-x86_64.tar.gz")
      assert(tool.download.archive.exists(_.files.head.from == "alpha-1.2.3/alpha"))
      assert(tool.symlinks.head.target == "/home/test/.apps/alpha-1.2.3/bin/alpha")

    test("kubectl stable text resolves through a fake HTTP client"):
      val plan = resolve(kubectlResolverYaml, FakeHttpTextClient("v1.33.0"))

      val tool = onlyTool(plan)
      assert(ResolvedVersion.render(tool.version) == "v1.33.0")
      assert(tool.download.url == "https://dl.k8s.io/release/v1.33.0/bin/linux/amd64/kubectl")

    test("host selectors exclude non-matching tools before version resolution"):
      val requestedUrls = ConcurrentLinkedQueue[String]()
      val client        = new HttpTextClient:
        def getText(url: String): Either[HttpTextError, String] =
          requestedUrls.add(url)
          Right("1.0.0")

      val profile = ConfigModule.loadString(hostSelectedYaml) match
        case Right(value) => value
        case Left(error)  => abort(s"expected valid config, got $error")
      val options = ResolutionOptions(
        Map("HOME" -> "/home/test"),
        SensitiveValueRedactions.empty,
        HostPlatform("linux", "amd64")
      )
      val plan = PlanResolver.resolve(profile, options, client) match
        case Right(value) => value
        case Left(error)  => abort(s"expected resolved plan, got $error")

      assert(plan.tools.map(_.name.value) == Vector("linux-tool"))
      assert(requestedUrls.asScala.toVector == Vector("https://example.invalid/linux-version"))

    test("manifest fingerprint includes every supported host selector field"):
      val original  = ConfigModule.loadString(hostSelectedYaml).toOption.get
      val osChanged = ConfigModule.loadString(
        hostSelectedYaml.replace("family: linux", "family: darwin")
      ).toOption.get
      val architectureChanged = ConfigModule.loadString(
        hostSelectedYaml.replace("architecture: x86_64", "architecture: arm64")
      ).toOption.get

      assert(ManifestFingerprint.profile(original) != ManifestFingerprint.profile(osChanged))
      assert(ManifestFingerprint.profile(original) !=
        ManifestFingerprint.profile(architectureChanged))

    test("dynamic latest-url remains dynamic without a concrete version"):
      val plan = resolve(dynamicLatestUrlYaml)

      val tool = onlyTool(plan)
      assert(tool.version == ResolvedVersion.DynamicLatestUrl(Some("upstream latest endpoint")))
      assert(ResolvedVersion.render(tool.version) == "dynamic latest-url")
      assert(tool.download.url == "https://example.invalid/latest/download/beta")

    test("strict policy rejects dynamic versions and missing checksums"):
      val errors = resolveErrors(strictPolicyYaml())

      assert(errors.exists(error =>
        error.path == "spec.versions.alpha.dynamic.type" &&
          error.message.contains("strict-policy[dynamic-latest-url]") &&
          error.message.contains("suggestion[dynamic-latest-url]")
      ))
      assert(errors.exists(error =>
        error.path == "spec.plan[0].spec.download.checksum" &&
          error.message.contains("strict-policy[missing-checksum]") &&
          error.message.contains("suggestion[missing-checksum]")
      ))

    test("strict policy permits risky behavior only through explicit overrides"):
      val plan = resolve(strictPolicyYaml(
        """allowDynamicLatestUrls: true
          |    allowMissingChecksums: true""".stripMargin
      ))

      val tool = onlyTool(plan)
      assert(plan.policy.mode == binstaller.config.PolicyMode.Strict)
      assert(plan.policy.allowDynamicLatestUrls == PolicyAllowance.Allowed)
      assert(plan.policy.allowMissingChecksums == PolicyAllowance.Allowed)
      assert(tool.download.archive.exists(_.original.archiveType == ArchiveType.TarXz))

    test("unresolved variables and missing version values produce validation-style errors"):
      val errors = resolveErrors(invalidVariablesYaml)

      assert(errors.exists(errorAt("spec.plan[0].spec.installDir")))
      assert(errors.exists(errorAt("spec.plan[1].spec.download.url")))
      assert(errors.exists(_.message.contains("unresolved variable 'MISSING'")))
      assert(errors.exists(_.message.contains("no concrete version is available")))

    test("a failing download.filename is reported once, not once per resolver"):
      // `filename` is resolved inside the download block; nothing above it may resolve it a
      // second time, or every filename problem reaches the user twice.
      val errors = resolveErrors(invalidVariablesYaml.replace(
        "          filename: alpha",
        """          filename: "${MISSING}""""
      ))

      assert(errors.count(errorAt("spec.plan[0].spec.download.filename")) == 1)

    test("a plan renders from an in-memory manifest, without touching the filesystem"):
      // The manifest source is a boundary like every other. A configPath that does not exist
      // proves the use case never reaches the disk to find one.
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(RoutingBinaryDownloadClient.success, InstallFileSystem.nio),
        resolutionOptions = testResolutionOptions,
        profileSource = ProfileSource.yamlText(shellSyntaxYaml)
      )

      val result = service.plan(applyOptions(Path.of("/nonexistent/never-read.yaml")))

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(result.lines.exists(_.contains("alpha")))

    test("apply parallelism cannot be constructed below 1"):
      assert(ApplyParallelism.fromInt(0) == Left(ApplyParallelismError.NotPositive(0)))
      assert(ApplyParallelism.fromInt(-3) == Left(ApplyParallelismError.NotPositive(-3)))
      assert(ApplyParallelism.fromInt(1).map(_.value) == Right(1))

    test("shell command substitution is text and is never executed"):
      val plan = resolve(shellSyntaxYaml)

      val tool = onlyTool(plan)
      assert(tool.installDir == "/home/test/.apps/$(echo should-not-run)")
      assert(tool.download.url == "https://example.invalid/alpha")

    test("non-https version and download URLs fail resolution"):
      val errors = resolveErrors(insecureUrlYaml)

      assert(errors.exists(error =>
        error.path == "spec.versions.alpha.resolver.url" &&
          error.message.contains("URL must use https")
      ))
      assert(errors.exists(error =>
        error.path == "spec.plan[0].spec.download.url" &&
          error.message.contains("URL must use https")
      ))

    test("HTTPS URL validation is case-insensitive and blocks local network targets"):
      assert(HttpsUrl.fromString("HTTPS://example.com/tool").isRight)
      assert(HttpsUrl.fromString("https://localhost/tool").isLeft)
      assert(HttpsUrl.fromString("https://127.0.0.1/tool").isLeft)
      assert(HttpsUrl.fromString("https://169.254.169.254/latest/meta-data").isLeft)
      assert(HttpsUrl.fromString("https://[::1]/tool").isLeft)

    test("runtime interpolation exposes only a non-secret environment allowlist"):
      assert(RuntimeTemplateEnvironment.allowed.contains("HOME"))
      assert(!RuntimeTemplateEnvironment.allowed.contains("AWS_SECRET_ACCESS_KEY"))
      assert(!RuntimeTemplateEnvironment.allowed.contains("GITHUB_TOKEN"))

    test("install directories must stay inside appsDir and not overlap"):
      val errors = resolveErrors(unsafeInstallDirYaml)

      assert(errors.exists(error =>
        error.path == "spec.plan[0].spec.installDir" &&
          error.message.contains("inside spec.policy.appsDir")
      ))
      assert(errors.exists(error =>
        error.path == "spec.plan[2].spec.installDir" &&
          error.message.contains("nested inside tool 'beta'")
      ))

    test("a direct binary tool with multiple executables is rejected at plan time"):
      val installDir    = tempDirectory("multi-exec").resolve("alpha")
      val multiExecYaml = directBinaryYaml(installDir).replace(
        "          - path: bin/alpha",
        "          - path: bin/alpha\n          - path: bin/beta"
      )
      val errors = resolveErrors(multiExecYaml)

      assert(errors.exists(error =>
        error.path == "spec.plan[0].spec.executables" &&
          error.message.contains("exactly one executable")
      ))

    test("interpolated path fields are revalidated after variables resolve"):
      val errors = resolveErrors(unsafeInterpolatedPathsYaml)

      assert(errors.exists(errorAt("spec.policy.stateFile")))
      assert(errors.exists(errorAt("spec.plan[0].spec.createDirectories[0]")))
      assert(errors.exists(errorAt("spec.plan[0].spec.download.filename")))
      assert(errors.exists(errorAt("spec.plan[0].spec.download.archive.extract.files[0].to")))
      assert(errors.exists(errorAt("spec.plan[0].spec.executables[0].path")))
      assert(errors.exists(errorAt("spec.plan[0].spec.symlinks[0].path")))
      assert(errors.exists(errorAt("spec.plan[0].spec.symlinks[0].target")))
      assert(errors.exists(errorAt("spec.plan[1].spec.installDir")))
      assert(errors.exists(errorAt("spec.plan[2].spec.installDir")))
      assert(errors.exists(_.message.contains("control")))
      assert(errors.exists(_.message.contains("traversal")))
      assert(errors.exists(_.message.contains("relative")))
      assert(errors.exists(_.message.contains("inside spec.policy.appsDir")))

    test("example config resolves expected install directories under appsDir"):
      val plan = resolveExampleConfig(FakeHttpTextClient("v1.33.0"))

      assert(plan.policy.appsDir == "/home/test/.apps")
      assert(plan.tools.map(tool => tool.name.value -> tool.installDir) ==
        exampleToolNames.map(name => name -> s"/home/test/.apps/$name"))
      assert(plan.tools.forall(_.installDir.startsWith(s"${plan.policy.appsDir}/")))

    test("invalid config reports every aggregated validation error concisely"):
      val tempRoot = tempDirectory("core-invalid-config")
      val config   = writeConfig(tempRoot, invalidConfigYaml(tempRoot))
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = service.plan(applyOptions(config))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.startsWith("apiVersion: unsupported value")))
      assert(result.lines.exists(_.startsWith("kind: unsupported value")))
      assert(
        result.lines.exists(_.startsWith("spec.policy.continueOnError: value must be a boolean"))
      )
      assert(!result.lines.exists(_.contains("ValidationFailed")))
      assert(!result.lines.exists(_.contains("Exception")))

    test("plan renders strict policy failures with typed suggestions"):
      val tempRoot = tempDirectory("core-strict-policy-output")
      val config   = writeConfig(tempRoot, strictPolicyYaml())
      val service  = BinaryInstallerService.resolving(FakeHttpTextClient(""))

      val planResult = service.plan(InstallerOptions(
        configPath = config.toString,
        statePath = None,
        resetState = ResetState.Disabled,
        verboseOutput = VerboseOutput.Disabled
      ))

      assert(planResult.status == InstallerRunStatus.Failed)
      assert(planResult.lines.exists(_.contains("strict-policy[missing-checksum]")))
      assert(planResult.lines.exists(_.contains("suggestion[missing-checksum]")))
