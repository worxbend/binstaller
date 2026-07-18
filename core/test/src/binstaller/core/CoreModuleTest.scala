package binstaller.core

import binstaller.config.ChecksumAlgorithm
import binstaller.config.ConfigModule
import binstaller.config.ExecutableMode
import binstaller.config.ArchiveType
import binstaller.config.AllowSudoSymlinks
import binstaller.config.SymlinkPrivilege
import utest.*

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*
import scala.util.Using
import upickle.default.read
import upickle.default.write

object CoreModuleTest extends TestSuite with CoreTestSupport:

  val tests: Tests = Tests:
    test("module path includes config before core"):
      assert(CoreModule.modulePath == Vector("config", "core"))

    test("https url validation accepts uppercase scheme and public hosts"):
      assert(HttpsUrl.fromString("HTTPS://example.com/tool.tar.gz").isRight)
      assert(HttpsUrl.fromString("https://example.com/tool.tar.gz").isRight)

    test("https url validation rejects non-https and userinfo-bearing urls"):
      assert(HttpsUrl.fromString("http://example.com/tool").isLeft)
      assert(HttpsUrl.fromString("https://user:pass@example.com/tool").isLeft)

    test("network target guard rejects loopback link-local and metadata hosts"):
      val blocked = Vector(
        "localhost",
        "localhost.",
        "sub.localhost",
        "service.local",
        "127.0.0.1",
        "0.0.0.0",
        "0.1.2.3",
        "100.64.0.1",
        "100.127.255.1",
        "169.254.169.254",
        "10.0.0.5",
        "192.168.1.10",
        "metadata.google.internal",
        "[::1]",
        "[fc00::1]",
        "[fd12:3456:789a::1]"
      )
      blocked.foreach: host =>
        assert(NetworkTargetGuard.validate(host).isLeft)

    test("network target guard allows ordinary public hosts and ip literals"):
      assert(NetworkTargetGuard.validate("example.com").isRight)
      assert(NetworkTargetGuard.validate("8.8.8.8").isRight)
      assert(NetworkTargetGuard.validate("101.64.0.1").isRight)

    test("validateResolved fails closed for unresolvable hosts"):
      assert(NetworkTargetGuard.validateResolved("does-not-exist.invalid").isLeft)

    test("guarded resolver drops blocked addresses and fails closed when none remain"):
      val privateAddr = InetAddress.getByName("10.0.0.5")
      val publicAddr  = InetAddress.getByName("8.8.8.8")
      val filtered = GuardedInetAddressResolverProvider
        .guard("mixed.example", java.util.stream.Stream.of(publicAddr, privateAddr))
        .iterator()
        .asScala
        .toVector
      assert(filtered == Vector(publicAddr))
      val rebindOnly = scala.util.Try(
        GuardedInetAddressResolverProvider
          .guard("rebind.example", java.util.stream.Stream.of(privateAddr))
          .iterator()
          .asScala
          .toVector
      )
      assert(rebindOnly.failed.toOption.exists(_.isInstanceOf[java.net.UnknownHostException]))

    test("installer event context measures elapsed time from injected monotonic clock"):
      var now     = 1_000L
      val context = InstallerEventContext.start(InstallerEventObserver.none, () => now)

      now = 1_250L

      assert(context.elapsedTime == Duration.ofNanos(250L))

    test("run statistics use structured results rather than rendered wording"):
      val statistics = InstallerRunStatistics.fromResult(InstallerResult(
        lines = Vector("wording can change freely"),
        exitCode = 1,
        terminalResults = Vector(
          TerminalToolResult.Completed("alpha", "/apps/alpha"),
          TerminalToolResult.Failed("beta", "boom")
        ),
        skippedTools = 3
      ))

      assert(statistics == InstallerRunStatistics(installed = 1, failed = 1, skipped = 3))

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

      assert(plan.tools.map(_.name) == Vector("linux-tool"))
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

    test("JDK http-text client records direct no-redirect provenance"):
      val response = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/stable.txt",
        responseStatusCode = 200,
        responseBody = ByteArrayInputStream("v1.0.0".getBytes(StandardCharsets.UTF_8))
      )
      val client = JdkHttpTextClient(StaticHttpClient(response), _ => Right(()))

      val result = client.getTextWithProvenance("https://example.invalid/stable.txt")

      result match
        case Right(value) =>
          assert(value.text == "v1.0.0")
          assert(value.provenance.initialUrl == "https://example.invalid/stable.txt")
          assert(value.provenance.finalUrl == "https://example.invalid/stable.txt")
          assert(value.provenance.redirects.isEmpty)
        case Left(error) => abort(s"expected text response, got $error")

    test("JDK http-text client records multiple redirects"):
      val first = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/stable.txt",
        responseStatusCode = 302,
        responseBody = ByteArrayInputStream(Array.emptyByteArray)
      )
      val second = FakeHttpResponse[InputStream](
        responseUri = "https://cdn.example.invalid/releases/stable.txt",
        responseStatusCode = 301,
        responseBody = ByteArrayInputStream(Array.emptyByteArray),
        previous = Some(first)
      )
      val finalResponse = FakeHttpResponse[InputStream](
        responseUri = "https://mirror.example.invalid/releases/stable.txt",
        responseStatusCode = 200,
        responseBody = ByteArrayInputStream("v1.0.1".getBytes(StandardCharsets.UTF_8)),
        previous = Some(second)
      )
      val client = JdkHttpTextClient(StaticHttpClient(finalResponse), _ => Right(()))

      val result = client.getTextWithProvenance("https://example.invalid/stable.txt")

      result match
        case Right(value) =>
          assert(value.text == "v1.0.1")
          assert(value.provenance.initialUrl == "https://example.invalid/stable.txt")
          assert(value.provenance.finalUrl == "https://mirror.example.invalid/releases/stable.txt")
          assert(value.provenance.redirects.map(_.statusCode) == Vector(302, 301))
          assert(value.provenance.redirects.map(_.from) ==
            Vector(
              "https://example.invalid/stable.txt",
              "https://cdn.example.invalid/releases/stable.txt"
            ))
          assert(value.provenance.redirects.map(_.to) ==
            Vector(
              "https://cdn.example.invalid/releases/stable.txt",
              "https://mirror.example.invalid/releases/stable.txt"
            ))
        case Left(error) => abort(s"expected text response, got $error")

    test("HTTP clients reject local redirect targets before following them"):
      val redirect = FakeHttpResponse[InputStream](
        responseUri = "https://example.invalid/stable.txt",
        responseStatusCode = 302,
        responseBody = ByteArrayInputStream(Array.emptyByteArray),
        responseHeaders = Map("Location" -> Vector("https://169.254.169.254/latest/meta-data"))
      )
      val client = JdkHttpTextClient(StaticHttpClient(redirect), _ => Right(()))

      val result = client.getTextWithProvenance("https://example.invalid/stable.txt")

      assert(result.left.exists(_.message.contains("unsafe redirect target")))

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
      val installDir = Files.createTempDirectory("binstaller-multi-exec").resolve("alpha")
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
      assert(plan.tools.map(tool => tool.name -> tool.installDir) ==
        exampleToolNames.map(name => name -> s"/home/test/.apps/$name"))
      assert(plan.tools.forall(_.installDir.startsWith(s"${plan.policy.appsDir}/")))

    test("direct binary install writes download bytes to first executable path"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-direct")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes),
        InstallFileSystem.nio
      )

      val result = installer.installTool(directTool(installDir))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "alpha-binary")

    test("sha256 mismatch fails before replacing an existing install"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-checksum")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val tool = directTool(
        installDir,
        checksum = Some(ResolvedChecksum(
          ChecksumAlgorithm.Sha256,
          "0" * 64,
          ResolvedChecksumSource.Configured
        ))
      )
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("replacement".getBytes),
        InstallFileSystem.nio
      )

      val result = installer.installTool(tool)

      assert(result.isLeft)
      assert(result.left.exists(_.isInstanceOf[ToolInstallError.ChecksumMismatch]))
      assert(Files.readString(existingFile) == "existing")

    test("executable modes use four-digit octal strings and default to 0755"):
      val fileSystem = RecordingInstallFileSystem(stagedFiles = Vector("bin/alpha", "bin/helper"))
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha".getBytes),
        fileSystem
      )
      val tool = directTool(
        Path.of("/tmp/alpha"),
        executables = Vector(
          ResolvedExecutable("bin/alpha", Some(ExecutableMode("0700"))),
          ResolvedExecutable("bin/helper", None)
        )
      )

      val result = installer.installTool(tool)

      assertInstallSuccess(result, "/tmp/alpha")
      assert(fileSystem.recordedModes.map(request => request.path -> request.mode.octal) ==
        Vector("bin/alpha" -> "0700", "bin/helper" -> "0755"))
      assert(fileSystem.recordedModes.map(_.mode.numeric) == Vector(448, 493))

    test("download failure preserves existing install and returns a typed error"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-download")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.failure("network unavailable"),
        InstallFileSystem.nio
      )

      val result = installer.installTool(directTool(installDir))

      assert(result ==
        Left(
          ToolInstallError.DownloadFailed(
            "alpha",
            "https://example.invalid/alpha",
            "network unavailable"
          )
        ))
      assert(Files.readString(existingFile) == "existing")

    test("JDK binary download client records direct no-redirect provenance"):
      val response = FakeHttpResponse[ByteArrayInputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = ByteArrayInputStream("alpha".getBytes(StandardCharsets.UTF_8)),
        responseHeaders = Map("Content-Length" -> Vector("5"))
      )
      val client = JdkBinaryDownloadClient(StaticHttpClient(response), hostGuard = _ => Right(()))

      val result = client.downloadWithProvenance("https://example.invalid/alpha")

      result match
        case Right(value) =>
          assert(String(value.bytes, StandardCharsets.UTF_8) == "alpha")
          assert(value.provenance == UrlProvenance.direct("https://example.invalid/alpha"))
        case Left(error) => abort(s"expected binary response, got $error")

    test("JDK binary download client records redirects and emits final URL progress"):
      val first = FakeHttpResponse[ByteArrayInputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 302,
        responseBody = ByteArrayInputStream(Array.emptyByteArray)
      )
      val finalResponse = FakeHttpResponse[ByteArrayInputStream](
        responseUri = "https://cdn.example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = ByteArrayInputStream("alpha".getBytes(StandardCharsets.UTF_8)),
        previous = Some(first),
        responseHeaders = Map("Content-Length" -> Vector("5"))
      )
      val progress = RecordingBinaryDownloadProgressObserver()
      val client   = JdkBinaryDownloadClient(StaticHttpClient(finalResponse), hostGuard = _ => Right(()))

      val result = client.downloadWithProvenance("https://example.invalid/alpha", progress)

      result match
        case Right(value) =>
          assert(String(value.bytes, StandardCharsets.UTF_8) == "alpha")
          assert(value.provenance.initialUrl == "https://example.invalid/alpha")
          assert(value.provenance.finalUrl == "https://cdn.example.invalid/alpha")
          assert(value.provenance.redirects ==
            Vector(UrlRedirectHop(
              "https://example.invalid/alpha",
              "https://cdn.example.invalid/alpha",
              302
            )))
          assert(progress.urls.distinct == Vector("https://cdn.example.invalid/alpha"))
        case Left(error) => abort(s"expected binary response, got $error")

    test("bounded body reader rejects oversized content length before buffering"):
      val result = BoundedBinaryBodyReader.read(
        "https://example.invalid/alpha",
        ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),
        Some(11L),
        BinaryDownloadLimits(maxBytes = 10L, bodyTimeout = Duration.ofSeconds(5)),
        BinaryDownloadProgressObserver.none
      )

      assert(result.left.exists(_.message.contains("exceeds max allowed 10 bytes")))

    test("bounded body reader stops downloads that exceed max size without content length"):
      val result = BoundedBinaryBodyReader.read(
        "https://example.invalid/alpha",
        ByteArrayInputStream("oversized-body".getBytes(StandardCharsets.UTF_8)),
        None,
        BinaryDownloadLimits(maxBytes = 4L, bodyTimeout = Duration.ofSeconds(5)),
        BinaryDownloadProgressObserver.none
      )

      assert(result.left.exists(_.message.contains("exceeds max allowed 4 bytes")))

    test("bounded body reader fails when body read exceeds deadline"):
      var now    = 0L
      val result = BoundedBinaryBodyReader.read(
        "https://example.invalid/alpha",
        ByteArrayInputStream("abcdef".getBytes(StandardCharsets.UTF_8)),
        None,
        BinaryDownloadLimits(maxBytes = 1024L, bodyTimeout = Duration.ofNanos(1)),
        BinaryDownloadProgressObserver.none,
        nowNanos = () =>
          now = now + 2L
          now
      )

      assert(result.left.exists(_.message.contains("download body timed out")))

    test("staging failure preserves existing install and does not replace"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-staging")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val fileSystem = RecordingInstallFileSystem(stageFailure = Some("disk full"))
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("replacement".getBytes),
        fileSystem
      )

      val result = installer.installTool(directTool(installDir))

      assert(result == Left(ToolInstallError.StagingFailed("alpha", "disk full")))
      assert(fileSystem.replaceCalls == 0)
      assert(Files.readString(existingFile) == "existing")

    test("mode application failure preserves existing install and does not replace"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-mode")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val fileSystem = RecordingInstallFileSystem(modeFailure = Some("permission denied"))
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("replacement".getBytes),
        fileSystem
      )

      val result = installer.installTool(directTool(installDir))

      assert(result ==
        Left(
          ToolInstallError.ModeApplicationFailed(
            "alpha",
            "bin/alpha",
            "0755",
            "permission denied"
          )
        ))
      assert(fileSystem.replaceCalls == 0)
      assert(Files.readString(existingFile) == "existing")

    test("apply renders expected executor failures without throwing"):
      val tempRoot = Files.createTempDirectory("binstaller-core-cli-error")
      val config   = tempRoot.resolve("profile.yaml")
      Files.writeString(config, directBinaryYaml(tempRoot.resolve("alpha")))
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(
          FakeBinaryDownloadClient.failure("network unavailable"),
          InstallFileSystem.nio
        )
      )

      val result = service.apply(
        InstallerOptions(
          configPath = config.toString,
          statePath = None,
          resetState = ResetState.Disabled,
          verboseOutput = VerboseOutput.Disabled
        )
      )

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("failed alpha: download:")))
      assert(result.lines.exists(_.contains("network unavailable")))
      assert(!result.lines.exists(_.contains("Exception")))
      assert(!result.lines.exists(_.contains("at binstaller.")))

    test("invalid config reports every aggregated validation error concisely"):
      val tempRoot = Files.createTempDirectory("binstaller-core-invalid-config")
      val config   = writeConfig(tempRoot, invalidConfigYaml(tempRoot))
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = service.plan(applyOptions(config))

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.startsWith("apiVersion: unsupported value")))
      assert(result.lines.exists(_.startsWith("kind: unsupported value")))
      assert(
        result.lines.exists(_.startsWith("spec.policy.continueOnError: value must be a boolean"))
      )
      assert(!result.lines.exists(_.contains("ValidationFailed")))
      assert(!result.lines.exists(_.contains("Exception")))

    test("versions output includes package version summary table"):
      val service = BinaryInstallerService.resolving(FakeHttpTextClient("v1.34.0"))
      val result  = service.versions(applyOptions(exampleConfigPath))

      assert(result.exitCode == 0)
      assert(result.lines.exists(line =>
        line.startsWith("package") && line.endsWith("newer version")
      ))
      // yazi and kustomize are GitHub release-download tools; the fake client cannot reach the
      // GitHub latest-release API, so their status is unknown ("?") rather than a false "-".
      assert(versionSummaryRowExists(result.lines, "yazi", "v26.5.6", "?"))
      assert(versionSummaryRowExists(result.lines, "helm", "v3.21.2", "-"))
      assert(versionSummaryRowExists(result.lines, "kustomize", "v5.8.1", "?"))
      assert(versionSummaryRowExists(result.lines, "kubectl", "v1.34.0", "-"))
      assert(versionSummaryRowExists(result.lines, "minikube", "dynamic latest-url", "-"))
      assert(!result.lines.exists(_.contains("https://")))
      assert(!result.lines.exists(_.contains("final url")))

    test("versions output reports newer GitHub release for pinned downloads"):
      val tempRoot = Files.createTempDirectory("binstaller-core-github-latest")
      val config   = writeConfig(tempRoot, githubReleaseYaml(tempRoot, "0.40.0"))
      val service  = BinaryInstallerService.resolving(
        RoutingHttpTextClient(Map(
          "https://api.github.com/repos/jj-vcs/jj/releases/latest" -> Right(HttpTextResponse(
            """{"tag_name":"v0.41.0"}""",
            UrlProvenance.direct("https://api.github.com/repos/jj-vcs/jj/releases/latest")
          ))
        ))
      )

      val result = service.versions(applyOptions(config))

      assert(result.exitCode == 0)
      assert(versionSummaryRowExists(result.lines, "jujutsu", "0.40.0", "v0.41.0"))
      assert(!result.lines.exists(_.contains("github:")))

    test("semantic version ordering distinguishes stable and prerelease versions"):
      assert(VersionOrdering.compare("v1.2.0", "v1.2.0-rc.1") == VersionOrder.Greater)
      assert(VersionOrdering.compare("v1.2.0-rc.2", "v1.2.0-rc.1") == VersionOrder.Greater)
      assert(VersionOrdering.compare("v1.2.0-rc.1", "v1.2.0") == VersionOrder.Less)

    test("sha256sum lookup prefers exact path over basename and resolves it"):
      val content = s"${"a" * 64}  linux/tool\n${"b" * 64}  darwin/tool\n"
      assert(Sha256SumChecksumFile.find(content, "linux/tool") == Sha256SumChecksumFile.Lookup.Found(
        "a" * 64
      ))
      assert(
        Sha256SumChecksumFile.find(content, "darwin/tool") == Sha256SumChecksumFile.Lookup.Found(
          "b" * 64
        )
      )

    test("sha256sum lookup reports ambiguity for basename-colliding entries"):
      val content = s"${"a" * 64}  linux/tool\n${"b" * 64}  darwin/tool\n"
      Sha256SumChecksumFile.find(content, "tool") match
        case Sha256SumChecksumFile.Lookup.Ambiguous(paths) =>
          assert(paths == Vector("linux/tool", "darwin/tool"))
        case other => abort(s"expected ambiguous lookup, got $other")

    test("sha256sum lookup collapses duplicate identical digests to a single found entry"):
      val content = s"${"c" * 64}  tool\n${"C" * 64} *tool\n"
      assert(Sha256SumChecksumFile.find(content, "tool") == Sha256SumChecksumFile.Lookup.Found(
        "c" * 64
      ))

    test("sha256sum lookup reports not found when no entry matches"):
      val content = s"${"a" * 64}  other\n"
      assert(Sha256SumChecksumFile.find(content, "tool") == Sha256SumChecksumFile.Lookup.NotFound)

    test("sha256sum lookup unescapes GNU backslash-prefixed filenames"):
      val content = s"\\${"a" * 64}  weird\\\\name\n"
      assert(Sha256SumChecksumFile.find(content, "weird\\name") ==
        Sha256SumChecksumFile.Lookup.Found("a" * 64))

    test("versions output flags unavailable GitHub release metadata without failing"):
      val tempRoot = Files.createTempDirectory("binstaller-core-github-unavailable")
      val config   = writeConfig(tempRoot, githubReleaseYaml(tempRoot, "0.40.0"))
      val service  = BinaryInstallerService.resolving(
        RoutingHttpTextClient(Map(
          "https://api.github.com/repos/jj-vcs/jj/releases/latest" ->
            Left(HttpTextError(
              "https://api.github.com/repos/jj-vcs/jj/releases/latest",
              "HTTP 403"
            ))
        ))
      )

      val result = service.versions(applyOptions(config))

      assert(result.exitCode == 0)
      // A failed latest-release fetch renders "?" so it is distinguishable from a genuine "-".
      assert(versionSummaryRowExists(result.lines, "jujutsu", "0.40.0", "?"))
      assert(!result.lines.exists(_.contains("HTTP 403")))

    test("lock writes pinned http-text and dynamic source metadata without apply state"):
      val tempRoot = Files.createTempDirectory("binstaller-core-lock")
      val config   = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath = tempRoot.resolve("resolved.lock.json")
      val service  = BinaryInstallerService.resolving(
        LockHttpTextClient(
          "2.0.0",
          UrlProvenance(
            "https://example.invalid/beta-version",
            "https://cdn.example.invalid/beta-version",
            Vector(UrlRedirectHop(
              "https://example.invalid/beta-version",
              "https://cdn.example.invalid/beta-version",
              302
            ))
          )
        ),
        DirectBinaryInstaller(
          FakeBinaryDownloadClient.failure("lock must not download"),
          InstallFileSystem.nio
        ),
        ApplyStateStore.nio(tempRoot),
        RoutingBinaryMetadataClient(Map(
          "https://example.invalid/alpha-1.0.0" -> BinaryMetadata(
            Some(11L),
            UrlProvenance.direct("https://example.invalid/alpha-1.0.0"),
            Some(Sha256Digest.trusted("a" * 64))
          ),
          "https://example.invalid/beta-2.0.0" -> BinaryMetadata(
            Some(22L),
            UrlProvenance(
              "https://example.invalid/beta-2.0.0",
              "https://cdn.example.invalid/beta-2.0.0",
              Vector(UrlRedirectHop(
                "https://example.invalid/beta-2.0.0",
                "https://cdn.example.invalid/beta-2.0.0",
                301
              ))
            ),
            Some(Sha256Digest.trusted("b" * 64))
          ),
          "https://example.invalid/latest/gamma" -> BinaryMetadata(
            None,
            UrlProvenance.direct("https://example.invalid/latest/gamma"),
            Some(Sha256Digest.trusted("c" * 64))
          )
        )),
        LockFileStore.nio
      )

      val result = service.lock(applyOptions(config), LockOptions(lockPath.toString))
      val lock   = read[LockFile](Files.readString(lockPath))
      val tools  = lock.tools.map(tool => tool.name -> tool).toMap

      assert(result.exitCode == 0)
      assert(result.lines.exists(_.contains("wrote lock file")))
      assert(lock.schemaVersion == LockFile.schemaVersion)
      assert(lock.profileName == "lock-profile")
      assert(lock.manifestFingerprint.nonEmpty)
      assert(lock.tools.map(_.name) == Vector("alpha", "beta", "gamma"))
      assert(tools("alpha").resolvedVersion.contains("1.0.0"))
      assert(tools("alpha").versionProvenance.isEmpty)
      assert(tools("alpha").downloadProvenance.finalUrl == "https://example.invalid/alpha-1.0.0")
      assert(tools("alpha").sizeBytes.contains(11L))
      assert(tools("alpha").checksum.contains(LockFileChecksum("sha256", "a" * 64)))
      assert(!tools("alpha").dynamicSource)
      assert(tools("beta").resolvedVersion.contains("2.0.0"))
      assert(tools("beta").versionProvenance.exists(_.finalUrl ==
        "https://cdn.example.invalid/beta-version"))
      assert(tools("beta").downloadProvenance.finalUrl == "https://cdn.example.invalid/beta-2.0.0")
      assert(tools("beta").sizeBytes.contains(22L))
      assert(!tools("beta").dynamicSource)
      assert(tools("gamma").resolvedVersion.isEmpty)
      assert(tools("gamma").versionProvenance.isEmpty)
      assert(tools("gamma").sizeBytes.isEmpty)
      assert(tools("gamma").checksum.exists(_.value == "c" * 64))
      assert(tools("gamma").dynamicSource)
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))
      assert(!Files.exists(tempRoot.resolve("apps")))

    test("discovered checksum succeeds and is visible in plan versions and lock output"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-checksum-discovered")
      val artifactBytes   = "alpha-binary".getBytes(StandardCharsets.UTF_8)
      val artifactHash    = sha256(artifactBytes)
      val checksumFileUrl = "https://example.invalid/releases/1.0.0/SHA256SUMS"
      val config          = writeConfig(tempRoot, checksumDiscoveryYaml(tempRoot, checksumFileUrl))
      val lockPath        = tempRoot.resolve("checksum.lock.json")
      val service         = BinaryInstallerService.resolving(
        RoutingHttpTextClient(Map(
          checksumFileUrl -> Right(HttpTextResponse(
            s"$artifactHash  alpha-1.0.0.tar.gz\n",
            UrlProvenance.direct(checksumFileUrl)
          ))
        )),
        DirectBinaryInstaller(
          RoutingBinaryDownloadClient(Map(
            "https://example.invalid/releases/1.0.0/alpha-1.0.0.tar.gz" -> Right(artifactBytes)
          )),
          InstallFileSystem.nio
        ),
        ApplyStateStore.nio(tempRoot),
        RoutingBinaryMetadataClient(Map(
          "https://example.invalid/releases/1.0.0/alpha-1.0.0.tar.gz" -> BinaryMetadata(
            Some(artifactBytes.length.toLong),
            UrlProvenance.direct("https://example.invalid/releases/1.0.0/alpha-1.0.0.tar.gz"),
            Some(Sha256Digest.trusted(artifactHash))
          )
        )),
        LockFileStore.nio
      )

      val planResult     = service.plan(applyOptions(config))
      val versionsResult = service.versions(applyOptions(config))
      val applyResult    = service.apply(applyOptions(config))
      val lockResult     = service.lock(applyOptions(config), LockOptions(lockPath.toString))
      val lock           = read[LockFile](Files.readString(lockPath))

      assert(planResult.exitCode == 0)
      assert(planResult.lines.exists(_.contains(s"checksum: sha256 $artifactHash (discovered")))
      assert(planResult.lines.exists(_.contains(checksumFileUrl)))
      assert(versionsResult.exitCode == 0)
      assert(versionSummaryRowExists(versionsResult.lines, "alpha", "1.0.0", "-"))
      assert(!versionsResult.lines.exists(_.contains("checksums:")))
      assert(applyResult.exitCode == 0)
      assert(Files.readString(tempRoot.resolve("apps/alpha/bin/alpha")) == "alpha-binary")
      assert(lockResult.exitCode == 0)
      assert(lockResult.lines.exists(_.contains(
        "checksums: configured 0, discovered 1, inspected 0, missing 0"
      )))
      assert(lock.tools.head.checksum.exists(checksum =>
        checksum.source == "discovered" &&
          checksum.discoveryUrl.contains(checksumFileUrl) &&
          checksum.discoveryFile.contains("alpha-1.0.0.tar.gz")
      ))

    test("ambiguous discovered checksum fails resolution with a colliding-path diagnostic"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-checksum-ambiguous")
      val checksumFileUrl = "https://example.invalid/releases/1.0.0/SHA256SUMS"
      val config          = writeConfig(tempRoot, checksumDiscoveryYaml(tempRoot, checksumFileUrl))
      val service         = BinaryInstallerService.resolving(
        RoutingHttpTextClient(Map(
          checksumFileUrl -> Right(HttpTextResponse(
            s"${"a" * 64}  linux/alpha-1.0.0.tar.gz\n${"b" * 64}  darwin/alpha-1.0.0.tar.gz\n",
            UrlProvenance.direct(checksumFileUrl)
          ))
        ))
      )

      val result = service.plan(applyOptions(config))

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains(
        "checksum discovery found multiple sha256sum entries matching 'alpha-1.0.0.tar.gz'"
      )))
      assert(result.lines.exists(line =>
        line.contains("linux/alpha-1.0.0.tar.gz") && line.contains("darwin/alpha-1.0.0.tar.gz")
      ))

    test("missing checksum file fails resolution with a typed diagnostic"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-checksum-missing-file")
      val checksumFileUrl = "https://example.invalid/releases/1.0.0/SHA256SUMS"
      val config          = writeConfig(tempRoot, checksumDiscoveryYaml(tempRoot, checksumFileUrl))
      val service         = BinaryInstallerService.resolving(
        RoutingHttpTextClient(Map(
          checksumFileUrl -> Left(HttpTextError(checksumFileUrl, "HTTP 404"))
        ))
      )

      val result = service.plan(applyOptions(config))

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("checksum discovery failed: HTTP 404")))
      assert(result.lines.exists(_.contains("spec.plan[0].spec.download.checksum.discover.url")))

    test("mismatched discovered checksum fails before replacement"):
      val tempRoot = Files.createTempDirectory("binstaller-core-checksum-discovered-mismatch")
      val checksumFileUrl = "https://example.invalid/releases/1.0.0/SHA256SUMS"
      val config          = writeConfig(tempRoot, checksumDiscoveryYaml(tempRoot, checksumFileUrl))
      val existingFile    = tempRoot.resolve("apps/alpha/bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val service = BinaryInstallerService.resolving(
        RoutingHttpTextClient(Map(
          checksumFileUrl -> Right(HttpTextResponse(
            s"${"0" * 64}  alpha-1.0.0.tar.gz\n",
            UrlProvenance.direct(checksumFileUrl)
          ))
        )),
        DirectBinaryInstaller(
          RoutingBinaryDownloadClient(Map(
            "https://example.invalid/releases/1.0.0/alpha-1.0.0.tar.gz" ->
              Right("replacement".getBytes(StandardCharsets.UTF_8))
          )),
          InstallFileSystem.nio
        ),
        ApplyStateStore.nio(tempRoot)
      )

      val result = service.apply(applyOptions(config))
      val output = result.lines.mkString("\n")

      assert(result.exitCode == 1)
      assert(output.contains("checksum: sha256 expected"))
      assert(output.contains("checksum source: discovered"))
      assert(output.contains(checksumFileUrl))
      assert(Files.readString(existingFile) == "existing")

    test("locked plan validates lock and renders locked provenance without writes"):
      val tempRoot = Files.createTempDirectory("binstaller-core-locked-plan")
      val config   = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath = tempRoot.resolve("binstaller.lock.json")
      writeLock(lockPath, currentLockFile(config, dynamicSize = Some(33L)))
      val service = lockedApplyService(
        tempRoot,
        dynamicSize = Some(33L),
        installer = DirectBinaryInstaller(
          FakeBinaryDownloadClient.failure("locked plan must not download"),
          InstallFileSystem.nio
        )
      )

      val result = service.plan(
        applyOptions(config).copy(
          lockPath = lockPath.toString,
          lockedApply = LockedApplyMode.Enabled
        )
      )

      assert(result.exitCode == 0)
      assert(result.lines.exists(_.startsWith("lock file: ")))
      assert(result.lines.exists(_.contains("(validated)")))
      assert(result.lines.exists(
        _.contains("locked download final url: https://cdn.example.invalid/beta-2.0.0")
      ))
      assert(result.lines.exists(
        _.contains("locked version final url: https://cdn.example.invalid/beta-version")
      ))
      assert(!Files.exists(tempRoot.resolve("apps")))
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))

    test("locked apply rejects stale manifest fingerprint before install"):
      val tempRoot  = Files.createTempDirectory("binstaller-core-locked-stale")
      val config    = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath  = tempRoot.resolve("binstaller.lock.json")
      val staleLock = currentLockFile(config, dynamicSize = Some(33L)).copy(
        manifestFingerprint = "stale-fingerprint"
      )
      writeLock(lockPath, staleLock)
      val service = lockedApplyService(tempRoot, dynamicSize = Some(33L))

      val result = service.apply(
        applyOptions(config).copy(
          lockPath = lockPath.toString,
          lockedApply = LockedApplyMode.Enabled
        )
      )

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("manifest fingerprint changed")))
      assert(!Files.exists(tempRoot.resolve("apps/alpha")))
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))

    test("locked apply rejects download provenance drift before install"):
      val tempRoot  = Files.createTempDirectory("binstaller-core-locked-url-drift")
      val config    = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath  = tempRoot.resolve("binstaller.lock.json")
      val staleBeta = currentLockFile(config, dynamicSize = Some(33L)).tools.map:
        case tool if tool.name == "beta" =>
          tool.copy(downloadProvenance =
            UrlProvenance(
              "https://example.invalid/beta-2.0.0",
              "https://old-cdn.example.invalid/beta-2.0.0",
              Vector(UrlRedirectHop(
                "https://example.invalid/beta-2.0.0",
                "https://old-cdn.example.invalid/beta-2.0.0",
                301
              ))
            )
          )
        case tool => tool
      writeLock(lockPath, currentLockFile(config, dynamicSize = Some(33L)).copy(tools = staleBeta))
      val service = lockedApplyService(tempRoot, dynamicSize = Some(33L))

      val result = service.apply(
        applyOptions(config).copy(
          lockPath = lockPath.toString,
          lockedApply = LockedApplyMode.Enabled
        )
      )

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("download provenance changed")))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))

    test("locked apply rejects missing dynamic lock data"):
      val tempRoot = Files.createTempDirectory("binstaller-core-locked-dynamic-missing")
      val config   = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath = tempRoot.resolve("binstaller.lock.json")
      writeLock(lockPath, currentLockFile(config, dynamicSize = None))
      val service = lockedApplyService(tempRoot, dynamicSize = Some(33L))

      val result = service.plan(
        applyOptions(config).copy(
          lockPath = lockPath.toString,
          lockedApply = LockedApplyMode.Enabled
        )
      )

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("no locked sha256 digest")))
      assert(!Files.exists(tempRoot.resolve("apps")))
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))

    test("locked apply rejects missing lock before install"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-locked-missing")
      val installDir = tempRoot.resolve("alpha")
      val config     = writeConfig(tempRoot, directBinaryYaml(installDir))
      val lockPath   = tempRoot.resolve("missing.lock.json")
      val service    = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = service.apply(
        applyOptions(config).copy(
          lockPath = lockPath.toString,
          lockedApply = LockedApplyMode.Enabled
        )
      )

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("is missing")))
      assert(!Files.exists(installDir))

    test("locked apply verifies digest against bytes from the installation GET"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-locked-get-digest")
      val installDir   = tempRoot.resolve("alpha")
      val config       = writeConfig(tempRoot, directBinaryYaml(installDir))
      val lockPath     = tempRoot.resolve("binstaller.lock.json")
      val expected     = "expected".getBytes(StandardCharsets.UTF_8)
      val changed      = "changed!".getBytes(StandardCharsets.UTF_8)
      val expectedHash = sha256(expected)
      val profile      = ConfigModule.load(config) match
        case Right(value) => value
        case Left(error)  => abort(s"expected valid config, got $error")
      val url = "https://example.invalid/alpha"
      writeLock(
        lockPath,
        LockFile(
          LockFile.schemaVersion,
          profile.metadata.name,
          ManifestFingerprint.profile(profile),
          Vector(LockFileTool(
            "alpha",
            Some("1.0.0"),
            None,
            UrlProvenance.direct(url),
            Some(expected.length.toLong),
            Some(LockFileChecksum("sha256", expectedHash, "inspected", None, None, None)),
            false
          ))
        )
      )
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(
          RoutingBinaryDownloadClient(Map(url -> Right(changed))),
          InstallFileSystem.nio
        ),
        ApplyStateStore.nio(tempRoot),
        RoutingBinaryMetadataClient(Map(url -> BinaryMetadata(
          Some(expected.length.toLong),
          UrlProvenance.direct(url),
          Some(Sha256Digest.trusted(expectedHash))
        ))),
        LockFileStore.nio
      )

      val result = service.apply(applyOptions(config).copy(
        lockPath = lockPath.toString,
        lockedApply = LockedApplyMode.Enabled
      ))

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("checksum: sha256 expected")))
      assert(!Files.exists(installDir))

    test("apply installs a resolved plan"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-confirm")
      val installDir = tempRoot.resolve("alpha")
      val config     = writeConfig(tempRoot, directBinaryYaml(installDir))
      val service    = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = service.apply(applyOptions(config))

      assert(result.exitCode == 0)
      assert(Files.exists(installDir.resolve("bin/alpha")))

    test("continueOnError false stops apply after the first failed tool"):
      val tempRoot = Files.createTempDirectory("binstaller-core-stop-on-error")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "stop.state.json"))
      val service  = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Left("network unavailable"),
          "https://example.invalid/beta"  -> Right("beta".getBytes(StandardCharsets.UTF_8))
        ))
      )

      val result = service.apply(applyOptions(config))

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.startsWith("failed alpha: download:")))
      assert(!result.lines.exists(_.contains("installed beta")))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))
      assert(!hasStagedInstall(tempRoot, "beta"))

    test("continueOnError true continues apply after failed tools"):
      val tempRoot = Files.createTempDirectory("binstaller-core-continue-on-error")
      val config   = writeConfig(
        tempRoot,
        twoToolYaml(tempRoot, "continue.state.json", continueOnError = true)
      )
      val service = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Left("network unavailable"),
          "https://example.invalid/beta"  -> Right("beta".getBytes(StandardCharsets.UTF_8))
        ))
      )

      val result = service.apply(applyOptions(config))

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.startsWith("failed alpha: download:")))
      assert(result.lines.exists(_.contains("installed beta")))
      assert(Files.isRegularFile(tempRoot.resolve("apps/beta/bin/beta")))

    test("apply downloads and stages tools concurrently up to configured parallelism"):
      val tempRoot = Files.createTempDirectory("binstaller-core-parallel-downloads")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "parallel.state.json"))
      val client   = ConcurrentTrackingDownloadClient(
        Vector("https://example.invalid/alpha", "https://example.invalid/beta")
      )
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(client, InstallFileSystem.nio),
        ApplyStateStore.nio(tempRoot)
      )

      val result = service.apply(applyOptions(config).copy(applyParallelism = ApplyParallelism(2)))

      assert(result.exitCode == 0)
      assert(client.maxInFlight >= 2)
      assert(Files.isRegularFile(tempRoot.resolve("apps/alpha/bin/alpha")))
      assert(Files.isRegularFile(tempRoot.resolve("apps/beta/bin/beta")))

    test("service honors apply parallelism of one"):
      val tempRoot = Files.createTempDirectory("binstaller-core-serial-downloads")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "serial.state.json"))
      val client   = ParallelismProbeDownloadClient()
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(client, InstallFileSystem.nio),
        ApplyStateStore.nio(tempRoot)
      )

      val result = service.apply(applyOptions(config).copy(applyParallelism = ApplyParallelism(1)))

      assert(result.exitCode == 0)
      assert(client.maxInFlight == 1)

    test("sudo password requests stay serialized after parallel downloads"):
      val tempRoot = Files.createTempDirectory("binstaller-core-parallel-sudo")
      val config   = writeConfig(tempRoot, twoSudoToolYaml(tempRoot))
      val client   = ConcurrentTrackingDownloadClient(
        Vector("https://example.invalid/alpha", "https://example.invalid/beta")
      )
      val credentials = ConcurrentTrackingSudoCredentialProvider()
      val service     = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(
          client,
          InstallFileSystem.nio,
          PasswordPromptCommandExecutor(),
          credentials
        ),
        ApplyStateStore.nio(tempRoot)
      )

      val result = service.apply(applyOptions(config).copy(applyParallelism = ApplyParallelism(2)))

      assert(result.exitCode == 0)
      assert(client.maxInFlight >= 2)
      assert(credentials.maxInFlight == 1)
      assert(credentials.toolNames == Vector("alpha", "beta"))

    test("zip archive file mapping lands at configured relative target path"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-zip")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(zipArchive(Vector("pkg/alpha" -> "zip-alpha"))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.Zip,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "zip-alpha")

    test("tar.gz archive file mapping lands at configured relative target path"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-targz")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(tarGzArchive(Vector("pkg/alpha" -> "tar-alpha"))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "tar-alpha")

    test("tar.gz directory mapping moves extracted root directory into install root"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-targz-dir")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(tarGzArchive(Vector(
          "alpha-root/bin/alpha"    -> "alpha",
          "alpha-root/share/readme" -> "docs"
        ))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        directories = Vector("alpha-root" -> ".")
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "alpha")
      assert(Files.readString(installDir.resolve("share/readme")) == "docs")

    test("tar.gz root directory entries do not fail extraction"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-targz-root-dir")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(tarGzArchiveWithDirectories(
          directories = Vector("./"),
          files = Vector("./jj" -> "jujutsu")
        )),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("jj" -> "bin/jj"),
        executable = "bin/jj"
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/jj")) == "jujutsu")

    test("archive entries that escape staging are rejected and preserve existing install"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-zip-slip")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(zipArchive(Vector("../evil" -> "bad"))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.Zip,
        files = Vector("../evil" -> "bin/alpha")
      ))

      assert(result.left.exists(_.isInstanceOf[ToolInstallError.ArchiveExtractionFailed]))
      assert(Files.readString(existingFile) == "existing")

    test("duplicate zip archive members are rejected before replacement"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-zip-duplicate")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(zipArchiveWithDuplicateLocalEntries(Vector(
          "pkg/alpha" -> "first",
          "pkg/alpha" -> "second"
        ))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.Zip,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assert(result.left.exists:
        case ToolInstallError.ArchiveExtractionFailed(_, message) =>
          message.contains("duplicate archive member")
        case _ => false)
      assert(Files.readString(existingFile) == "existing")

    test("tar.gz hardlink metadata is rejected before replacement"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-targz-hardlink")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(tarGzArchiveWithEntryTypes(Vector(
          ("pkg/alpha", "", '1')
        ))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assert(result.left.exists:
        case ToolInstallError.ArchiveExtractionFailed(_, message) =>
          message.contains("unsafe archive link entry")
        case _ => false)
      assert(Files.readString(existingFile) == "existing")

    test("tar.xz extraction uses the validated in-process archive path"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-tarxz")
      val installDir      = tempRoot.resolve("zig")
      val commandExecutor = FakeArchiveCommandExecutor("zig-root/bin/zig", "zig")
      val installer       = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(tarXzArchive(Vector("zig-root/bin/zig" -> "zig"))),
        InstallFileSystem.nio,
        commandExecutor
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarXz,
        directories = Vector("zig-root" -> "."),
        executable = "bin/zig"
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/zig")) == "zig")
      assert(commandExecutor.commands.isEmpty)

    test("archive extraction enforces an aggregate expanded-byte budget"):
      assert(ArchiveExtractor.validateExtractedSize(ArchiveExtractor.maxExtractedBytes).isRight)
      assert(ArchiveExtractor.validateExtractedSize(ArchiveExtractor.maxExtractedBytes + 1).isLeft)

    test("tar.gz unplanned member exceeding the byte budget is rejected during the single pass"):
      // Regression guard for the decompression-bomb DoS: an unplanned member declaring more bytes
      // than the aggregate budget must be rejected even though NONE of the bomb members are part of
      // the copy plan. The previous two-pass extractor skipped unplanned members with no budget.
      val tempRoot   = Files.createTempDirectory("binstaller-core-targz-bomb")
      val installDir = tempRoot.resolve("alpha")
      val output     = java.io.ByteArrayOutputStream()
      val gzip       = java.util.zip.GZIPOutputStream(output)
      val planned    = "pkg-alpha".getBytes(StandardCharsets.UTF_8)
      gzip.write(tarHeader("pkg/alpha", planned.length, '0'))
      gzip.write(planned)
      gzip.write(Array.fill[Byte]((512 - (planned.length % 512)) % 512)(0))
      // Unplanned member declaring 2 GiB while carrying no payload; extraction must not inflate it.
      gzip.write(tarHeader("pkg/bomb", 2000000000, '0'))
      gzip.write(Array.fill[Byte](1024)(0))
      gzip.close()
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(output.toByteArray),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assert(result.left.exists:
        case ToolInstallError.ArchiveExtractionFailed(_, message) =>
          message.contains("extracted byte limit")
        case _ => false)

    test("tar.gz archive exceeding the max entry count is rejected"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-targz-count")
      val installDir = tempRoot.resolve("alpha")
      val output     = java.io.ByteArrayOutputStream()
      val gzip       = java.util.zip.GZIPOutputStream(output)
      val total      = ArchiveExtractor.maxEntries + 1
      var index      = 0
      while index < total do
        gzip.write(tarHeader(s"pkg/file-$index", 0, '0'))
        index += 1
      gzip.write(Array.fill[Byte](1024)(0))
      gzip.close()
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(output.toByteArray),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("pkg/file-0" -> "bin/alpha")
      ))

      assert(result.left.exists:
        case ToolInstallError.ArchiveExtractionFailed(_, message) =>
          message.contains("max entry count")
        case _ => false)

    test("tar.gz base-256 encoded entry size is decoded without a NumberFormatException"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-targz-base256")
      val installDir = tempRoot.resolve("alpha")
      val content    = "base256".getBytes(StandardCharsets.UTF_8)
      val header     = tarHeader("pkg/alpha", 0, '0')
      // Overwrite the 12-byte size field (offset 124) with a GNU base-256 big-endian encoding: the
      // high bit of the first byte marks base-256, the value follows big-endian in the low bytes.
      header(124) = 0x80.toByte
      var index = 1
      while index < 12 do
        header(124 + index) = 0.toByte
        index += 1
      header(124 + 11) = content.length.toByte
      val output = java.io.ByteArrayOutputStream()
      val gzip   = java.util.zip.GZIPOutputStream(output)
      gzip.write(header)
      gzip.write(content)
      gzip.write(Array.fill[Byte]((512 - (content.length % 512)) % 512)(0))
      gzip.write(Array.fill[Byte](1024)(0))
      gzip.close()
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(output.toByteArray),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "base256")

    test("process command executor times out long-running commands"):
      val tempRoot = Files.createTempDirectory("binstaller-core-process-timeout")
      val executor = CommandExecutor.processWithTimeout(Duration.ofMillis(100))

      val result = executor.run(CommandSpec(
        Vector("sh", "-c", "sleep 2"),
        tempRoot,
        Map("PATH" -> sys.env.getOrElse("PATH", "/usr/bin:/bin"))
      ))

      assert(result.left.exists(_.message.contains("timed out")))

    test("process command executor captures stdout and stderr on failure"):
      val tempRoot = Files.createTempDirectory("binstaller-core-process-output")
      val executor = CommandExecutor.processWithTimeout(Duration.ofSeconds(5))

      val result = executor.run(CommandSpec(
        Vector("sh", "-c", "printf 'stdout-line\\n'; printf 'stderr-line\\n' >&2; exit 7"),
        tempRoot,
        Map("PATH" -> sys.env.getOrElse("PATH", "/usr/bin:/bin"))
      ))

      result match
        case Left(error) =>
          assert(error.exitCode.contains(7))
          assert(error.output.stdout.contains("stdout-line"))
          assert(error.output.stderr.contains("stderr-line"))
        case Right(()) => abort("expected command failure")

    test("production runtime-variable allowlist produces no redactions"):
      // Guards the documented invariant that redaction is empty in production: every allowlisted
      // env name is non-secret, so none is redacted (control scrubbing in display() is the
      // always-on protection).
      val allowlisted =
        RuntimeTemplateEnvironment.allowed.map(name => name -> "some-non-secret-value").toMap
      assert(
        SensitiveValueRedactions.fromRuntimeVariables(allowlisted) == SensitiveValueRedactions.empty
      )

    test("apply errors redact sensitive runtime values and scrub terminal controls"):
      val secret = "secret-token-value"
      val plan   = ResolvedPlan(
        ResolvedPolicy(
          "/tmp/apps",
          None,
          AllowSudoSymlinks.Disabled,
          ContinueOnError.Disabled
        ),
        Vector(directTool(Path.of("/tmp/apps/alpha")).copy(download =
          ResolvedDownload(
            url = s"https://example.invalid/$secret/alpha",
            filename = "alpha",
            checksum = None,
            archive = None
          )
        )),
        SensitiveValueRedactions(Vector(secret))
      )
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.failure(s"network \u001b[31m failure for $secret"),
        InstallFileSystem.nio
      )

      val result = installer.installPlan(plan)
      val output = result.lines.mkString("\n")

      assert(result.exitCode == 1)
      assert(!output.contains(secret))
      assert(!output.contains("\u001b"))
      assert(output.contains("<redacted>"))

    test("checksum mismatch diagnostics redact discovered checksum source"):
      val secret = "secret-token-value"
      val plan   = ResolvedPlan(
        ResolvedPolicy(
          "/tmp/apps",
          None,
          AllowSudoSymlinks.Disabled,
          ContinueOnError.Disabled
        ),
        Vector(directTool(Path.of("/tmp/apps/alpha")).copy(download =
          ResolvedDownload(
            url = "https://example.invalid/alpha",
            filename = "alpha",
            checksum = Some(ResolvedChecksum(
              ChecksumAlgorithm.Sha256,
              "0" * 64,
              ResolvedChecksumSource.Discovered(
                s"https://example.invalid/$secret/SHA256SUMS",
                "alpha",
                UrlProvenance.direct(s"https://example.invalid/$secret/SHA256SUMS")
              )
            )),
            archive = None
          )
        )),
        SensitiveValueRedactions(Vector(secret))
      )
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("replacement".getBytes(StandardCharsets.UTF_8)),
        RecordingInstallFileSystem(stagedFiles = Vector("bin/alpha"))
      )

      val result = installer.installPlan(plan)
      val output = result.lines.mkString("\n")

      assert(result.exitCode == 1)
      assert(output.contains(
        "checksum source: discovered from https://example.invalid/<redacted>/SHA256SUMS"
      ))
      assert(!output.contains(secret))

    test("apply output omits redirected download provenance while state records it"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-download-redirect-state")
      val config     = writeConfig(tempRoot, directBinaryYaml(tempRoot.resolve("alpha")))
      val stateStore = RecordingApplyStateStore(ApplyStateStore.nio(tempRoot))
      val download   = UrlProvenance(
        "https://example.invalid/alpha",
        "https://cdn.example.invalid/alpha",
        Vector(UrlRedirectHop(
          "https://example.invalid/alpha",
          "https://cdn.example.invalid/alpha",
          302
        ))
      )
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(RedirectingBinaryDownloadClient(download), InstallFileSystem.nio),
        stateStore
      )

      val result = service.apply(applyOptions(config).copy(statePath = Some("redirect.state.json")))

      assert(result.exitCode == 0)
      assert(!result.lines.exists(_.startsWith("download initial url:")))
      assert(!result.lines.exists(_.startsWith("download final url:")))
      assert(!result.lines.exists(_.startsWith("download redirects:")))
      assert(stateStore.savedStates.last.tools.head.download.contains(download))

    test("apply output omits sensitive redirected URLs"):
      val secret   = "secret-token-value"
      val download = UrlProvenance(
        "https://example.invalid/alpha",
        s"https://cdn.example.invalid/$secret/alpha",
        Vector(UrlRedirectHop(
          "https://example.invalid/alpha",
          s"https://cdn.example.invalid/$secret/alpha",
          302
        ))
      )
      val plan = ResolvedPlan(
        ResolvedPolicy(
          "/tmp/apps",
          None,
          AllowSudoSymlinks.Disabled,
          ContinueOnError.Disabled
        ),
        Vector(directTool(Path.of("/tmp/apps/alpha"))),
        SensitiveValueRedactions(Vector(secret))
      )
      val installer = DirectBinaryInstaller(
        RedirectingBinaryDownloadClient(download),
        RecordingInstallFileSystem(stagedFiles = Vector("bin/alpha"))
      )

      val result = installer.installPlan(plan)
      val output = result.lines.mkString("\n")

      assert(result.exitCode == 0)
      assert(!output.contains(secret))
      assert(!output.contains("download final url:"))
      assert(!output.contains("download redirects:"))

    test("failed replacement restores previous install directory"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-rollback")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val missingStaging = tempRoot.resolve("missing-stage")

      val result = InstallFileSystem.nio.replaceInstall(StagedInstall(missingStaging, installDir))

      assert(result.isLeft)
      assert(Files.readString(existingFile) == "existing")
      assert(!Using.resource(Files.list(tempRoot)): stream =>
        stream.iterator().asScala.exists(_.getFileName.toString.contains(".backup-")))

    test("staging reclaims stale sibling temp dirs but not fresh ones"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-sweep")
      val installDir = tempRoot.resolve("alpha")
      val staleOrphan = Files.createDirectory(tempRoot.resolve(".alpha.stage-stale"))
      Files.writeString(staleOrphan.resolve("leftover"), "x")
      val freshOrphan = Files.createDirectory(tempRoot.resolve(".alpha.backup-fresh"))
      val twoHoursAgo = java.nio.file.attribute.FileTime.fromMillis(
        System.currentTimeMillis() - java.time.Duration.ofHours(2).toMillis
      )
      val _ = Files.setLastModifiedTime(staleOrphan, twoHoursAgo)

      val staged = InstallFileSystem.nio.stageDirectBinary(
        installDir,
        Vector.empty,
        "bin/alpha",
        "alpha".getBytes(StandardCharsets.UTF_8)
      )

      assert(staged.isRight)
      assert(!Files.exists(staleOrphan))
      assert(Files.exists(freshOrphan))

    test("direct install verifies expected executables"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-direct-missing")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio
      )

      val result = installer.installTool(directTool(
        installDir,
        executables = Vector(
          ResolvedExecutable("bin/alpha", None),
          ResolvedExecutable("bin/missing", None)
        )
      ))

      assert(result == Left(ToolInstallError.MissingExecutable("alpha", "bin/missing")))
      assert(!hasStagedInstall(tempRoot, "alpha"))

    test("local symlinks are created under installDir with targets resolved from installDir"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-local-symlink")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio
      )
      val tool = directTool(
        installDir,
        symlinks = Vector(ResolvedSymlink("bin/a", "bin/alpha", SymlinkPrivilege.User))
      )

      val result = installer.installTool(tool)

      assertInstallSuccess(result, installDir.toString)
      assert(Files.isSymbolicLink(installDir.resolve("bin/a")))
      assert(Files.readSymbolicLink(installDir.resolve("bin/a")) ==
        installDir.toAbsolutePath.normalize().resolve("bin/alpha"))

    test("sudo symlink apply requires policy before writes"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-sudo-gate")
      val installDir      = tempRoot.resolve("alpha")
      val commandExecutor = RecordingCommandExecutor()
      val installer       = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor
      )
      val plan = ResolvedPlan(
        ResolvedPolicy(
          tempRoot.toString,
          None,
          AllowSudoSymlinks.Disabled,
          ContinueOnError.Disabled
        ),
        Vector(sudoSymlinkTool(installDir))
      )

      val result = installer.installPlan(plan)

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("policy.allowSudoSymlinks")))
      assert(commandExecutor.commands.isEmpty)
      assert(!Files.exists(installDir))

    test("sudo symlink apply uses structured argv after policy allowance"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-sudo-apply")
      val installDir      = tempRoot.resolve("alpha")
      val commandExecutor = RecordingCommandExecutor()
      val credentials     =
        RecordingSudoCredentialProvider(Right(SudoPassword.fromString("unused-secret")))
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val plan = ResolvedPlan(
        ResolvedPolicy(
          tempRoot.toString,
          None,
          AllowSudoSymlinks.Enabled,
          ContinueOnError.Disabled
        ),
        Vector(sudoSymlinkTool(installDir))
      )

      val result = installer.installPlan(plan)

      assert(result.exitCode == 0)
      assert(credentials.requests.isEmpty)
      assert(commandExecutor.commands.map(_.argv) == Vector(
        Vector("sudo", "-n", "true"),
        Vector(
          "sudo",
          "-n",
          "ln",
          "-sfn",
          installDir.toAbsolutePath.normalize().resolve("bin/alpha").toString,
          "/usr/local/bin/alpha"
        )
      ))
      assert(commandExecutor.commands.forall(_.env == CommandEnvironment.baseline))

    test("sudo symlink apply requests credentials when sudo cache is unavailable"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-sudo-credentials")
      val installDir      = tempRoot.resolve("alpha")
      val password        = "core-test-password"
      val commandExecutor =
        SequencedCommandExecutor(Vector(Left("sudo password required"), Right(())))
      val credentials = RecordingSudoCredentialProvider(Right(SudoPassword.fromString(password)))
      val installer   = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val plan = ResolvedPlan(
        ResolvedPolicy(
          tempRoot.toString,
          None,
          AllowSudoSymlinks.Enabled,
          ContinueOnError.Disabled
        ),
        Vector(sudoSymlinkTool(installDir))
      )

      val result = installer.installPlan(plan)

      assert(result.exitCode == 0)
      assert(credentials.requests == Vector(SudoCredentialRequest(
        "alpha",
        "/usr/local/bin/alpha",
        installDir.toAbsolutePath.normalize().resolve("bin/alpha").toString,
        s"create sudo symlink ${installDir.toAbsolutePath.normalize().resolve("bin/alpha")} -> /usr/local/bin/alpha"
      )))
      assert(commandExecutor.commands.map(_.argv) == Vector(
        Vector("sudo", "-n", "true"),
        Vector(
          "sudo",
          "-S",
          "-p",
          "",
          "ln",
          "-sfn",
          installDir.toAbsolutePath.normalize().resolve("bin/alpha").toString,
          "/usr/local/bin/alpha"
        )
      ))
      assert(!commandExecutor.commands.exists(_.argv.contains(password)))
      assert(!commandExecutor.commands.map(_.toString).exists(_.contains(password)))

    test("sudo credential cancellation fails current operation and continues when policy allows"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-sudo-cancel")
      val alphaInstall    = tempRoot.resolve("alpha")
      val betaInstall     = tempRoot.resolve("beta")
      val commandExecutor = SequencedCommandExecutor(Vector(Left("sudo password required")))
      val credentials     = RecordingSudoCredentialProvider(Left(SudoCredentialError.Canceled))
      val installer       = DirectBinaryInstaller(
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Right("alpha-binary".getBytes(StandardCharsets.UTF_8)),
          "https://example.invalid/beta"  -> Right("beta-binary".getBytes(StandardCharsets.UTF_8))
        )),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val beta = directTool(betaInstall).copy(name = "beta")
      val plan = ResolvedPlan(
        ResolvedPolicy(
          tempRoot.toString,
          None,
          AllowSudoSymlinks.Enabled,
          ContinueOnError.Enabled
        ),
        Vector(sudoSymlinkTool(alphaInstall), beta)
      )

      val result = installer.installPlan(plan)

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("sudo credentials canceled")))
      assert(result.lines.exists(_.contains("installed beta")))
      assert(credentials.requests.map(_.toolName) == Vector("alpha"))

    test("sudo command failure rendering redacts password from diagnostics"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-sudo-redaction")
      val installDir      = tempRoot.resolve("alpha")
      val password        = "super-secret-password"
      val commandExecutor = PasswordLeakingCommandExecutor(password)
      val credentials = RecordingSudoCredentialProvider(Right(SudoPassword.fromString(password)))
      val installer   = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val plan = ResolvedPlan(
        ResolvedPolicy(
          tempRoot.toString,
          None,
          AllowSudoSymlinks.Enabled,
          ContinueOnError.Disabled
        ),
        Vector(sudoSymlinkTool(installDir))
      )

      val result = installer.installPlan(plan)

      assert(result.exitCode == 1)
      assert(result.lines.mkString("\n").contains("<redacted>"))
      assert(!result.lines.mkString("\n").contains(password))
      assert(!commandExecutor.commands.exists(_.argv.contains(password)))
      assert(!commandExecutor.commands.map(_.toString).exists(_.contains(password)))

    test("sudo password is redacted from events and apply state"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-sudo-state-redaction")
      val installDir      = tempRoot.resolve("alpha")
      val stateFile       = "sudo-redaction.state.json"
      val password        = "state-secret-password"
      val commandExecutor = PasswordLeakingCommandExecutor(password)
      val credentials = RecordingSudoCredentialProvider(Right(SudoPassword.fromString(password)))
      val installer   = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        installer,
        ApplyStateStore.nio(tempRoot)
      )
      val config   = writeConfig(tempRoot, sudoSymlinkYaml(tempRoot, installDir, stateFile))
      val observer = RecordingInstallerEventObserver()

      val result   = service.applyWithEvents(applyOptions(config), observer)
      val state    = loadState(tempRoot, stateFile)
      val rendered =
        (result.lines ++
          observer.events.map(_.toString) ++
          state.tools.flatMap(_.message)).mkString("\n")

      assert(result.exitCode == 1)
      assert(rendered.contains("<redacted>"))
      assert(!rendered.contains(password))
      assert(!commandExecutor.commands.exists(_.argv.contains(password)))

    test("completed state entries are skipped and failed entries are retried"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-state-resume")
      val config       = writeConfig(tempRoot, twoToolYaml(tempRoot, "resume.state.json"))
      val firstService = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Right("alpha".getBytes(StandardCharsets.UTF_8)),
          "https://example.invalid/beta"  -> Left("network unavailable")
        ))
      )

      val firstResult = firstService.apply(applyOptions(config))

      assert(firstResult.exitCode == 1)
      assert(Files.isRegularFile(tempRoot.resolve("apps/alpha/bin/alpha")))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))

      val secondService = statefulService(tempRoot, RoutingBinaryDownloadClient.success)
      val skippedResult = secondService.apply(
        applyOptions(config).copy(selection = ToolSelection(Vector.empty, Vector("beta")))
      )

      assert(skippedResult.exitCode == 0)
      assert(skippedResult.lines == Vector("skipped alpha: already completed in state"))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))

      val retryResult = secondService.apply(applyOptions(config))
      val state       = loadState(tempRoot, "resume.state.json")

      assert(retryResult.exitCode == 0)
      assert(retryResult.lines.exists(_.contains("skipped alpha")))
      assert(retryResult.lines.exists(_.contains("installed beta")))
      assert(Files.isRegularFile(tempRoot.resolve("apps/beta/bin/beta")))
      assert(state.tools.map(tool => tool.name -> tool.status) ==
        Vector(
          "alpha" -> ApplyStateToolStatus.Completed,
          "beta"  -> ApplyStateToolStatus.Completed
        ))
      assert(!hasTempStateFile(tempRoot, "resume.state.json"))

    test("incompatible state fails clearly unless reset-state is enabled"):
      val tempRoot  = Files.createTempDirectory("binstaller-core-state-reset")
      val config    = writeConfig(tempRoot, twoToolYaml(tempRoot, "mismatch.state.json"))
      val store     = ApplyStateStore.nio(tempRoot)
      val statePath = tempRoot.resolve("mismatch.state.json")
      store.save(
        statePath,
        ApplyState.empty("other-profile", "other-fingerprint")
      ) match
        case Right(())   => ()
        case Left(error) => abort(s"failed to seed state: $error")
      val service = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val mismatchResult = service.apply(applyOptions(config))
      val resetResult    = service.apply(applyOptions(config).copy(resetState = ResetState.Enabled))

      assert(mismatchResult.exitCode == 1)
      assert(mismatchResult.lines.exists(_.contains("does not match this manifest")))
      assert(mismatchResult.lines.exists(_.contains("--reset-state")))
      assert(resetResult.exitCode == 0)
      assert(loadState(tempRoot, "mismatch.state.json").profileName == "resume-profile")

    test("state schema version is validated before resume"):
      val tempRoot  = Files.createTempDirectory("binstaller-core-state-schema")
      val stateFile = "schema.state.json"
      val config    = writeConfig(tempRoot, twoToolYaml(tempRoot, stateFile))
      val service   = statefulService(tempRoot, RoutingBinaryDownloadClient.success)
      assert(service.apply(applyOptions(config)).exitCode == 0)
      val store        = ApplyStateStore.nio(tempRoot)
      val incompatible = loadState(tempRoot, stateFile).copy(schemaVersion = 999)
      store.save(tempRoot.resolve(stateFile), incompatible) match
        case Left(error) => abort(s"failed to seed state: $error")
        case Right(())   => ()

      val result = service.apply(applyOptions(config))

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("schema version 999")))
      assert(result.lines.exists(_.contains("expected 1")))

    test("completed state is retried when installed executables disappear"):
      val tempRoot  = Files.createTempDirectory("binstaller-core-state-drift")
      val stateFile = "drift.state.json"
      val config    = writeConfig(tempRoot, twoToolYaml(tempRoot, stateFile))
      val service   = statefulService(tempRoot, RoutingBinaryDownloadClient.success)
      assert(service.apply(applyOptions(config)).exitCode == 0)
      val alpha = tempRoot.resolve("apps/alpha/bin/alpha")
      Files.delete(alpha)

      val result = service.apply(
        applyOptions(config).copy(selection = ToolSelection(Vector("alpha"), Vector.empty))
      )

      assert(result.exitCode == 0)
      assert(result.lines.exists(_.contains("installed alpha")))
      assert(Files.isRegularFile(alpha))

    test("state paths must be cwd-local filenames"):
      val tempRoot = Files.createTempDirectory("binstaller-core-state-path")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "valid.state.json"))
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val absoluteResult = service.apply(
        applyOptions(config).copy(statePath =
          Some(tempRoot.resolve("absolute.state.json").toString)
        )
      )
      val nestedResult = service.apply(
        applyOptions(config).copy(statePath = Some("nested/state.json"))
      )

      assert(absoluteResult.exitCode == 1)
      assert(absoluteResult.lines.exists(_.contains("absolute state paths are not allowed")))
      assert(nestedResult.exitCode == 1)
      assert(nestedResult.lines.exists(_.contains("current working directory")))
      assert(!Files.exists(tempRoot.resolve("apps")))

    test("state is saved after each terminal tool result"):
      val tempRoot = Files.createTempDirectory("binstaller-core-state-writes")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "writes.state.json"))
      val store    = RecordingApplyStateStore(ApplyStateStore.nio(tempRoot))
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(RoutingBinaryDownloadClient.success, InstallFileSystem.nio),
        store
      )

      val result = service.apply(applyOptions(config))

      assert(result.exitCode == 0)
      assert(store.savedStates.size == 2)
      assert(store.savedStates.map(_.tools.map(tool => tool.name -> tool.status)) ==
        Vector(
          Vector("alpha" -> ApplyStateToolStatus.Completed),
          Vector(
            "alpha" -> ApplyStateToolStatus.Completed,
            "beta"  -> ApplyStateToolStatus.Completed
          )
        ))

    test("apply state status remains serialized as a stable string"):
      val state = ApplyState.empty("profile", "fingerprint").copy(tools =
        Vector(ApplyStateTool(
          "alpha",
          ApplyStateToolStatus.Completed,
          Some("/tmp/apps/alpha"),
          None
        ))
      )

      val encoded = write(state, indent = 2)
      val decoded = read[ApplyState](encoded)

      assert(encoded.contains(""""status": "completed""""))
      assert(decoded.tools.head.status == ApplyStateToolStatus.Completed)

    test("apply state status decodes existing string state files"):
      val decoded = read[ApplyState](
        """
          |{
          |  "schemaVersion": 1,
          |  "profileName": "profile",
          |  "manifestFingerprint": "fingerprint",
          |  "tools": [
          |    {
          |      "name": "alpha",
          |      "status": "failed",
          |      "installDir": null,
          |      "message": "network unavailable",
          |      "download": null
          |    }
          |  ]
          |}
          |""".stripMargin
      )

      assert(decoded.tools.head.status == ApplyStateToolStatus.Failed)
      assert(decoded.tools.head.message.contains("network unavailable"))

    test("plan emits resolving plan-ready and summary events in order"):
      val tempRoot = Files.createTempDirectory("binstaller-core-events-plan")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "plan.state.json"))
      val observer = RecordingInstallerEventObserver()
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = service.planWithEvents(applyOptions(config), observer)

      assert(result.exitCode == 0)
      assert(eventIndex(observer.events, { case InstallerEvent.ResolvingStarted(_, _) => true }) <
        eventIndex(
          observer.events,
          {
            case InstallerEvent.PlanReady(Vector("alpha", "beta"), Some(_), _) => true
          }
        ))
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.PlanReady(Vector("alpha", "beta"), Some(_), _) => true
        }
      ) <
        eventIndex(
          observer.events,
          {
            case InstallerEvent.Summary(InstallerRunStatus.Succeeded, 0, 0, 0, 0, Some(_), _) =>
              true
          }
        ))

    test("successful apply emits tool start progress result and summary in order"):
      val tempRoot = Files.createTempDirectory("binstaller-core-events-success")
      val config   = writeConfig(tempRoot, directBinaryYaml(tempRoot.resolve("alpha")))
      val observer = RecordingInstallerEventObserver()
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(
          ProgressingBinaryDownloadClient("alpha-binary".getBytes(StandardCharsets.UTF_8)),
          InstallFileSystem.nio
        ),
        ApplyStateStore.nio(tempRoot)
      )

      val result = service.applyWithEvents(applyOptions(config), observer)

      assert(result.exitCode == 0)
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolStarted("alpha", InstallerPhase.Downloading, _) => true
        }
      ) < eventIndex(
        observer.events,
        {
          case InstallerEvent.DownloadProgress(
                "alpha",
                "https://example.invalid/alpha",
                _,
                Some(_),
                DownloadProgressStatus.Advanced,
                _
              ) => true
        }
      ))
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.DownloadProgress(_, _, _, _, DownloadProgressStatus.Finished, _) =>
            true
        }
      ) < eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolResult("alpha", ToolResultStatus.Completed, Some(_), None, _) =>
            true
        }
      ))
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolResult("alpha", ToolResultStatus.Completed, Some(_), None, _) =>
            true
        }
      ) < eventIndex(
        observer.events,
        {
          case InstallerEvent.Summary(InstallerRunStatus.Succeeded, 1, 0, 0, 0, None, _) => true
        }
      ))

    test("failed apply emits failed result with root-cause summary"):
      val tempRoot = Files.createTempDirectory("binstaller-core-events-failed")
      val config   = writeConfig(tempRoot, directBinaryYaml(tempRoot.resolve("alpha")))
      val observer = RecordingInstallerEventObserver()
      val service  = statefulService(tempRoot, FakeBinaryDownloadClient.failure("network down"))

      val result = service.applyWithEvents(applyOptions(config), observer)

      assert(result.exitCode == 1)
      assert(observer.events.exists:
        case InstallerEvent.ToolResult(
              "alpha",
              ToolResultStatus.Failed,
              None,
              Some(summary),
              _
            ) => summary.contains("download:") && summary.contains("network down")
        case _ => false)
      assert(observer.events.exists:
        case InstallerEvent.Summary(InstallerRunStatus.Failed, 0, 1, 0, 1, None, _) => true
        case _                                                                      => false)

    test("completed state entries emit skipped events with state file path"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-events-skipped")
      val config       = writeConfig(tempRoot, twoToolYaml(tempRoot, "resume.state.json"))
      val firstService = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Right("alpha".getBytes(StandardCharsets.UTF_8)),
          "https://example.invalid/beta"  -> Left("network unavailable")
        ))
      )
      val _             = firstService.apply(applyOptions(config))
      val observer      = RecordingInstallerEventObserver()
      val secondService = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = secondService.applyWithEvents(
        applyOptions(config).copy(selection = ToolSelection(Vector.empty, Vector("beta"))),
        observer
      )

      assert(result.exitCode == 0)
      val skipIndex = eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolSkipped("alpha", "already completed in state", Some(path), _) =>
            path.endsWith("resume.state.json")
        }
      )
      val summaryIndex = eventIndex(
        observer.events,
        {
          case InstallerEvent.Summary(InstallerRunStatus.Succeeded, 0, 0, 1, 0, Some(_), _) => true
        }
      )
      assert(skipIndex < summaryIndex)

    test("continue-on-error emits failed then completed results before failed summary"):
      val tempRoot = Files.createTempDirectory("binstaller-core-events-continue")
      val config   = writeConfig(
        tempRoot,
        twoToolYaml(tempRoot, "continue.state.json", continueOnError = true)
      )
      val observer = RecordingInstallerEventObserver()
      val service  = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Left("network unavailable"),
          "https://example.invalid/beta"  -> Right("beta".getBytes(StandardCharsets.UTF_8))
        ))
      )

      val result = service.applyWithEvents(applyOptions(config), observer)

      assert(result.exitCode == 1)
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolResult("alpha", ToolResultStatus.Failed, None, Some(_), _) => true
        }
      ) < eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolResult("beta", ToolResultStatus.Completed, Some(_), None, _) =>
            true
        }
      ))
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolResult("beta", ToolResultStatus.Completed, Some(_), None, _) =>
            true
        }
      ) < eventIndex(
        observer.events,
        {
          case InstallerEvent.Summary(InstallerRunStatus.Failed, 1, 1, 0, 1, Some(_), _) => true
        }
      ))

    test("plan renders strict policy failures with typed suggestions"):
      val tempRoot = Files.createTempDirectory("binstaller-core-strict-policy-output")
      val config   = writeConfig(tempRoot, strictPolicyYaml())
      val service  = BinaryInstallerService.resolving(FakeHttpTextClient(""))

      val planResult = service.plan(InstallerOptions(
        configPath = config.toString,
        statePath = None,
        resetState = ResetState.Disabled,
        verboseOutput = VerboseOutput.Disabled
      ))

      assert(planResult.exitCode == 1)
      assert(planResult.lines.exists(_.contains("strict-policy[missing-checksum]")))
      assert(planResult.lines.exists(_.contains("suggestion[missing-checksum]")))
