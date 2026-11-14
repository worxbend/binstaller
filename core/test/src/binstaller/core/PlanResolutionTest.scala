package binstaller.core

import binstaller.config.ConfigModule
import binstaller.config.ArchiveType
import binstaller.config.ValidationError
import utest.*

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** Turning a manifest into a `ResolvedPlan`: variables, versions, policy and path validation. */
object PlanResolutionTest extends TestSuite with CoreTestSupport:

  /** A plan-only service over an in-memory manifest; version requests answer from `responses`. */
  private def selectionService(
      yaml: String,
      responses: Map[String, Either[HttpTextError, HttpTextResponse]]
  ): BinaryInstallerService = BinaryInstallerService.resolving(
    RoutingHttpTextClient(responses),
    DirectBinaryInstaller(RoutingBinaryDownloadClient.success, InstallFileSystem.nio),
    resolutionOptions = testResolutionOptions,
    profileSource = ProfileSource.yamlText(yaml)
  )

  private def selectingOnly(names: String*): InstallerOptions =
    applyOptions(Path.of("/nonexistent/never-read.yaml"))
      .copy(selection = ToolSelection(names.toVector, Vector.empty))

  private def selectingSkip(names: String*): InstallerOptions =
    applyOptions(Path.of("/nonexistent/never-read.yaml"))
      .copy(selection = ToolSelection(Vector.empty, names.toVector))

  /** Apply options for the manifest at `config`, narrowed to the `--only` names. */
  private def onlyIn(config: Path, names: String*): InstallerOptions =
    applyOptions(config).copy(selection = ToolSelection(names.toVector, Vector.empty))

  /** An HTTP client that answers nothing and records every URL asked of it. */
  private final class RecordingHttpTextClient extends HttpTextClient:
    val requestedUrls: ConcurrentLinkedQueue[String] = ConcurrentLinkedQueue[String]()

    def getTextWithProvenance(url: String): Either[HttpTextError, HttpTextResponse] =
      requestedUrls.add(url)
      Left(HttpTextError(url, "no request is expected in this test"))

  private val linuxVersionUrl = "https://example.invalid/linux-version"

  private val linuxVersionResponse: Map[String, Either[HttpTextError, HttpTextResponse]] =
    Map(linuxVersionUrl -> Right(HttpTextResponse("1.0.0", UrlProvenance.direct(linuxVersionUrl))))

  /** A plan entry no test host matches, to push the entries after it down the manifest. */
  private val darwinOnlyEntry: String = """    - name: darwin-only
                                          |      kind: binary-tool
                                          |      when:
                                          |        os:
                                          |          family: darwin
                                          |      spec:
                                          |        versionRef: alpha
                                          |        installDir: "${appsDir}/darwin-only"
                                          |        download:
                                          |          url: https://example.invalid/darwin-only
                                          |          filename: darwin-only
                                          |        executables:
                                          |          - path: bin/darwin-only
                                          |""".stripMargin

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
        def getTextWithProvenance(url: String): Either[HttpTextError, HttpTextResponse] =
          requestedUrls.add(url)
          Right(HttpTextResponse("1.0.0", UrlProvenance.direct(url)))

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

    test("--only does not resolve the version source of a deselected tool"):
      val service = selectionService(lockYaml(tempDirectory("core-only-first")), Map.empty)

      val result = service.plan(selectingOnly("alpha"))

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(result.lines.contains("tools: 1"))

    test("--skip does not resolve the version source of the skipped tool"):
      val service = selectionService(lockYaml(tempDirectory("core-skip-first")), Map.empty)

      val result = service.plan(selectingSkip("beta"))

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(result.lines.contains("tools: 2"))

    test("--skip accepts a spec.plan name that its host selector already excludes"):
      val result = selectionService(hostSelectedYaml, linuxVersionResponse)
        .plan(selectingSkip("darwin-tool"))

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(result.lines.contains("tools: 1"))

    test("--only reports a host-excluded tool as excluded for this host, not as unknown"):
      val result = selectionService(hostSelectedYaml, linuxVersionResponse)
        .plan(selectingOnly("darwin-tool"))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines == Vector(
        "selection: tool 'darwin-tool' is excluded for this host (linux/amd64) by its when selector"
      ))

    test("an unknown --only name fails before any tool is resolved"):
      val service = selectionService(lockYaml(tempDirectory("core-unknown-first")), Map.empty)

      val result = service.plan(selectingOnly("alpha", "nope"))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines == Vector("selection: unknown tool 'nope'"))

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

    test("a dynamic-version rejection names the policy setting that caused it"):
      val strictDefault     = resolveErrors(strictPolicyYaml())
      val developerOverride = resolveErrors(
        strictPolicyYaml("allowDynamicLatestUrls: false").replace("mode: strict", "mode: developer")
      )

      assert(strictDefault.exists(error =>
        error.path == "spec.versions.alpha.dynamic.type" &&
          error.message.contains("are rejected by policy.mode=strict;")
      ))
      assert(developerOverride.exists(error =>
        error.path == "spec.versions.alpha.dynamic.type" &&
          error.message.contains("are rejected by spec.policy.allowDynamicLatestUrls: false;")
      ))
      assert(!developerOverride.exists(_.message.contains("policy.mode=strict")))

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

    test("strict policy rejects latest download URLs with a query or fragment"):
      val queryErrors = resolveErrors(strictPolicyYaml().replace(
        "https://example.invalid/latest/download/alpha.tar.xz",
        "https://example.invalid/latest?file=alpha.tar.xz"
      ))
      val fragmentErrors = resolveErrors(strictPolicyYaml().replace(
        "https://example.invalid/latest/download/alpha.tar.xz",
        "https://example.invalid/latest#alpha"
      ))

      assert(queryErrors.exists(error =>
        error.path == "spec.plan[0].spec.download.url" &&
          error.message.contains("strict-policy[dynamic-latest-url]")
      ))
      assert(fragmentErrors.exists(error =>
        error.path == "spec.plan[0].spec.download.url" &&
          error.message.contains("strict-policy[dynamic-latest-url]")
      ))

    test("unresolved variables and missing version values produce validation-style errors"):
      val errors = resolveErrors(invalidVariablesYaml)

      assert(errors.exists(errorAt("spec.plan[0].spec.installDir")))
      assert(errors.exists(errorAt("spec.plan[1].spec.download.url")))
      assert(errors.exists(_.message.contains("unresolved variable 'MISSING'")))
      assert(errors.exists(_.message.contains("no concrete version is available")))

    test(
      "a version endpoint that answers with nothing is reported once, not per field that uses it"
    ):
      // FakeHttpTextClient("") answers the version request with an empty body.
      val errors = resolveErrors(kubectlResolverYaml)

      assert(errors.map(_.path) == Vector("spec.versions.kubectl.resolver.url"))

    test("a failed version request is reported once, without an echo that the version is empty"):
      val unreachable = new HttpTextClient:
        def getTextWithProvenance(url: String): Either[HttpTextError, HttpTextResponse] =
          Left(HttpTextError(url, "connection refused"))
      val profile = ConfigModule.loadString(kubectlResolverYaml) match
        case Right(value) => value
        case Left(error)  => abort(s"expected valid config, got $error")

      val errors = PlanResolver.resolve(profile, testResolutionOptions, unreachable) match
        case Left(ResolvePlanError.ValidationFailed(found)) => found
        case Right(plan) => abort(s"expected resolution errors, got $plan")

      assert(errors.map(_.message) == Vector("http-text resolver failed: connection refused"))

    test("a failing download.filename is reported once, not once per resolver"):
      // `filename` is resolved inside the download block; nothing above it may resolve it a
      // second time, or every filename problem reaches the user twice.
      val errors = resolveErrors(invalidVariablesYaml.replace(
        "          filename: alpha",
        """          filename: "${MISSING}""""
      ))

      assert(errors.count(errorAt("spec.plan[0].spec.download.filename")) == 1)

    test("a spec.vars value that expands to another template is rejected, not left literal"):
      val errors = resolveErrors(
        validPinnedYaml
          .replace(
            "    linuxArch: x86_64",
            "    linuxArch: x86_64\n    root: \"${HOME}/opt\"\n    apps: \"${root}/apps\""
          )
          .replace("appsDir: \"${HOME}/.apps\"", "appsDir: \"${apps}\"")
      )

      assert(errors.exists(errorAt("spec.vars.apps")))

    test("a placeholder whose name the interpolator cannot match is rejected, not left literal"):
      val errors = resolveErrors(
        validPinnedYaml.replace("${appsDir}/alpha-${version}", "${appsDir}/alpha-${linux-arch}")
      )

      assert(errors.exists: error =>
        error.path == "spec.plan[0].spec.installDir" && error.message.contains("${linux-arch}"))

    test("a tilde-prefixed appsDir is rejected instead of resolving under the working directory"):
      val errors = resolveErrors(
        validPinnedYaml.replace("appsDir: \"${HOME}/.apps\"", "appsDir: \"~/.apps\"")
      )

      assert(errors.exists: error =>
        error.path == "spec.policy.appsDir" && error.message.contains("must not start with '~'"))

    test("a relative sudo symlink path is rejected at plan time"):
      val errors = resolveErrors(
        validPinnedYaml.replace(
          "          - path: bin/a\n",
          "          - path: bin/a\n            sudo: true\n"
        )
      )

      assert(errors.exists: error =>
        error.path == "spec.plan[0].spec.symlinks[0].path" &&
          error.message == "sudo symlink path must be absolute")

    test("a symlink whose path is its own target is rejected at plan time"):
      val errors = resolveErrors(
        validPinnedYaml.replace("          - path: bin/a\n", "          - path: bin/alpha\n")
      )

      assert(errors == Vector(ValidationError(
        "spec.plan[0].spec.symlinks[0].path",
        "symlink path must not equal its target"
      )))

    test("a resolved version carrying a line break is rejected before it reaches plan output"):
      val profile = ConfigModule.loadString(kubectlResolverYaml) match
        case Right(value) => value
        case Left(error)  => abort(s"expected valid config, got $error")
      val forgedVersion = "v1.33.0\n   checksum: sha256 forged (configured in manifest)"

      val result =
        PlanResolver.resolve(profile, testResolutionOptions, FakeHttpTextClient(forgedVersion))

      result match
        case Left(ResolvePlanError.ValidationFailed(errors)) => assert(errors.exists: error =>
            error.path == "spec.versions.kubectl.resolver.url" &&
              error.message.contains("control characters"))
        case other => abort(s"expected resolution errors, got $other")

    test("display scrubs C1 control characters and lock inspection errors are scrubbed"):
      val hostile = "bad\u001b[2Jheader\u009b2K"

      val lockLines = LockCommandError.renderLines(
        LockCommandError.InspectionFailed(binstaller.config.ToolName.trusted("alpha"), hostile)
      )

      assert(RenderSafety.display(hostile) == "bad?[2Jheader?2K")
      assert(lockLines == Vector("lock inspection failed for tool 'alpha': bad?[2Jheader?2K"))

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

    test("install directory errors keep the manifest index after a host-excluded entry"):
      val errors =
        resolveErrors(unsafeInstallDirYaml.replace("  plan:\n", s"  plan:\n$darwinOnlyEntry"))

      assert(errors.exists(error =>
        error.path == "spec.plan[1].spec.installDir" &&
          error.message.contains("inside spec.policy.appsDir")
      ))
      assert(errors.exists(error =>
        error.path == "spec.plan[3].spec.installDir" &&
          error.message.contains("nested inside tool 'beta'")
      ))
      assert(!errors.exists(errorAt("spec.plan[0].spec.installDir")))
      assert(!errors.exists(errorAt("spec.plan[2].spec.installDir")))

    test("--only refuses to install into the installDir of a deselected tool"):
      val tempRoot = tempDirectory("core-only-same-install-dir")
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)
      val first    = writeConfig(tempRoot, twoToolYaml(tempRoot, "first.state.json"))
      assert(service.apply(onlyIn(first, "alpha")).status == InstallerRunStatus.Succeeded)
      val shared = writeConfig(
        tempRoot,
        twoToolYaml(tempRoot, "second.state.json").replace("/apps/beta\"", "/apps/alpha\"")
      )

      val result = service.apply(onlyIn(shared, "beta"))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.contains(
        "spec.plan[1].spec.installDir: installDir must not be nested inside tool 'alpha' installDir"
      ))
      assert(Files.isRegularFile(tempRoot.resolve("apps/alpha/bin/alpha")))

    test("--only refuses an installDir that contains the installDir of a deselected tool"):
      val tempRoot = tempDirectory("core-only-parent-install-dir")
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)
      val nested   = twoToolYaml(tempRoot, "first.state.json")
        .replace("/apps/alpha\"", "/apps/suite/alpha\"")
      val first = writeConfig(tempRoot, nested)
      assert(service.apply(onlyIn(first, "alpha")).status == InstallerRunStatus.Succeeded)
      val parent = writeConfig(
        tempRoot,
        nested.replace("first.state.json", "second.state.json")
          .replace("/apps/beta\"", "/apps/suite\"")
      )

      val result = service.apply(onlyIn(parent, "beta"))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.contains(
        "spec.plan[1].spec.installDir: installDir must not contain the installDir of tool 'alpha'"
      ))
      assert(Files.isRegularFile(tempRoot.resolve("apps/suite/alpha/bin/alpha")))

    test("a deselected tool's pinned version places its installDir for the overlap check"):
      val tempRoot = tempDirectory("core-only-pinned-install-dir")
      val config   = writeConfig(
        tempRoot,
        twoToolYaml(tempRoot, "pinned.state.json")
          .replace("/apps/alpha\"", "/apps/shared-1.0.0\"")
          .replace("/apps/beta\"", "/apps/shared-${version}\"")
      )
      val service = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = service.plan(onlyIn(config, "alpha"))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.contains(
        "spec.plan[0].spec.installDir: installDir must not be nested inside tool 'beta' installDir"
      ))

    test("a deselected tool with a resolver version is compared without a request"):
      val tempRoot = tempDirectory("core-only-resolver-install-dir")
      val client   = RecordingHttpTextClient()
      val service  = BinaryInstallerService.resolving(
        client,
        DirectBinaryInstaller(RoutingBinaryDownloadClient.success, InstallFileSystem.nio),
        resolutionOptions = testResolutionOptions,
        profileSource =
          ProfileSource.yamlText(lockYaml(tempRoot).replace("/apps/beta\"", "/apps/alpha\""))
      )

      val result = service.plan(selectingOnly("alpha"))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.contains(
        "spec.plan[0].spec.installDir: installDir must not be nested inside tool 'beta' installDir"
      ))
      assert(client.requestedUrls.isEmpty)

    test("overlaps and errors among deselected tools do not fail the run"):
      val tempRoot                        = tempDirectory("core-only-bystanders")
      val yaml                            = lockYaml(tempRoot)
      def planOnlyAlpha(manifest: String) = selectionService(manifest, Map.empty)
        .plan(selectingOnly("alpha"))

      val sharedByBystanders = planOnlyAlpha(yaml.replace("/apps/gamma\"", "/apps/beta\""))
      val brokenBystander = planOnlyAlpha(yaml.replace("/apps/beta\"", "/apps/${MISSING}/beta\""))

      assert(sharedByBystanders.status == InstallerRunStatus.Succeeded)
      assert(brokenBystander.status == InstallerRunStatus.Succeeded)

    test("strict policy errors keep the manifest index after a host-excluded entry"):
      val errors =
        resolveErrors(strictPolicyYaml().replace("  plan:\n", s"  plan:\n$darwinOnlyEntry"))

      assert(errors.exists(error =>
        error.path == "spec.plan[1].spec.download.checksum" &&
          error.message.contains("strict-policy[missing-checksum]")
      ))
      assert(errors.exists(error =>
        error.path == "spec.plan[1].spec.download.url" &&
          error.message.contains("strict-policy[dynamic-latest-url]")
      ))
      assert(!errors.exists(_.path.startsWith("spec.plan[0]")))

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

    test("error line rendering applies the supplied redactions"):
      val secret     = "super-secret-resolver-token"
      val redactions = SensitiveValueRedactions(Vector(secret))

      val resolveLines = ResolvePlanError.renderLines(
        ResolvePlanError.SelectionFailed(Vector(s"token $secret rejected")),
        redactions
      )
      val lockLines = LockCommandError.renderLines(
        LockCommandError.InvalidPath(s"/locks/$secret.lock.json", "bad path"),
        redactions
      )
      val applyLines = LockedApplyError.renderLines(
        LockedApplyError.Incompatible(Path.of(s"/locks/$secret.lock.json"), "mismatch"),
        redactions
      )

      assert(resolveLines == Vector("selection: token <redacted> rejected"))
      assert(lockLines == Vector("lock path '/locks/<redacted>.lock.json' is invalid: bad path"))
      assert(applyLines == Vector(
        "locked apply refused by /locks/<redacted>.lock.json: mismatch"
      ))
