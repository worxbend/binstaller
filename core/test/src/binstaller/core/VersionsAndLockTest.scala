package binstaller.core

import binstaller.config.ChecksumAlgorithm
import binstaller.config.ConfigModule
import binstaller.config.Sha256Digest
import utest.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import upickle.default.read
import upickle.default.write

/** The `versions` and `lock` commands, checksum discovery, and locked apply. */
object VersionsAndLockTest extends TestSuite with CoreTestSupport:

  val tests: Tests = Tests:
    test("versions output includes package version summary table"):
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient("v1.34.0"),
        testResolutionOptions
      )
      val result = service.versions(applyOptions(exampleConfigPath))

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(result.lines.exists(line =>
        line.startsWith("package") && line.endsWith("newer version")
      ))
      // yazi and kustomize are GitHub release-download tools; the fake client cannot reach the
      // GitHub latest-release API, so their status is unknown ("?") rather than a false "-".
      // helm, kubectl and minikube are never checked at all (non-GitHub or dynamic-latest), so
      // they also render "?" — only a genuinely checked up-to-date tool may render "-".
      assert(versionSummaryRowExists(result.lines, "yazi", "v26.5.6", "?"))
      assert(versionSummaryRowExists(result.lines, "helm", "v3.21.2", "?"))
      assert(versionSummaryRowExists(result.lines, "kustomize", "v5.8.1", "?"))

      // The same facts also cross the boundary as data, so a renderer never has to split the
      // padded text back into columns to recover them.
      assert(result.versionRows.contains(
        VersionSummaryRow("helm", "v3.21.2", NewerVersionStatus.Unknown)
      ))
      assert(result.versionRows.contains(
        VersionSummaryRow("yazi", "v26.5.6", NewerVersionStatus.Unknown)
      ))
      assert(versionSummaryRowExists(result.lines, "kubectl", "v1.34.0", "?"))
      assert(versionSummaryRowExists(result.lines, "minikube", "dynamic latest-url", "?"))
      assert(!result.lines.exists(_.contains("https://")))
      assert(!result.lines.exists(_.contains("final url")))

    test("versions output reports newer GitHub release for pinned downloads"):
      val tempRoot = tempDirectory("core-github-latest")
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

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(versionSummaryRowExists(result.lines, "jujutsu", "0.40.0", "v0.41.0"))
      assert(!result.lines.exists(_.contains("github:")))

    test("versions checks the same GitHub repository once for several tools"):
      val apiUrl = "https://api.github.com/repos/jj-vcs/jj/releases/latest"
      val plan   = resolve(sameRepoGitHubYaml)
      val calls  = java.util.concurrent.atomic.AtomicInteger(0)
      val client = new HttpTextClient:
        def getTextWithProvenance(url: String): Either[HttpTextError, HttpTextResponse] =
          assert(url == apiUrl)
          val _ = calls.incrementAndGet()
          Right(HttpTextResponse("""{"tag_name":"v0.41.0"}""", UrlProvenance.direct(url)))

      val statuses = GitHubReleaseVersions.versionStatusByTool(plan, client)

      assert(calls.get() == 1)
      assert(statuses == Map(
        toolName("jujutsu")     -> GitHubReleaseVersions.LatestReleaseStatus.Newer("v0.41.0"),
        toolName("jujutsu-alt") -> GitHubReleaseVersions.LatestReleaseStatus.Newer("v0.41.0")
      ))

    test("semantic version ordering distinguishes stable and prerelease versions"):
      assert(VersionOrdering.compare("v1.2.0", "v1.2.0-rc.1") == VersionOrder.Greater)
      assert(VersionOrdering.compare("v1.2.0-rc.2", "v1.2.0-rc.1") == VersionOrder.Greater)
      assert(VersionOrdering.compare("v1.2.0-rc.1", "v1.2.0") == VersionOrder.Less)

    test("sha256sum lookup prefers exact path over basename and resolves it"):
      val content = s"${"a" * 64}  linux/tool\n${"b" * 64}  darwin/tool\n"
      assert(Sha256SumChecksumFile.find(content, "linux/tool") ==
        Sha256SumChecksumFile.Lookup.Found(digest("a" * 64)))
      assert(Sha256SumChecksumFile.find(content, "darwin/tool") ==
        Sha256SumChecksumFile.Lookup.Found(digest("b" * 64)))

    test("sha256sum lookup reports ambiguity for basename-colliding entries"):
      val content = s"${"a" * 64}  linux/tool\n${"b" * 64}  darwin/tool\n"
      Sha256SumChecksumFile.find(content, "tool") match
        case Sha256SumChecksumFile.Lookup.Ambiguous(paths) =>
          assert(paths == Vector("linux/tool", "darwin/tool"))
        case other => abort(s"expected ambiguous lookup, got $other")

    test("sha256sum lookup collapses duplicate identical digests to a single found entry"):
      val content = s"${"c" * 64}  tool\n${"C" * 64} *tool\n"
      assert(Sha256SumChecksumFile.find(content, "tool") ==
        Sha256SumChecksumFile.Lookup.Found(digest("c" * 64)))

    test("sha256sum lookup reports not found when no entry matches"):
      val content = s"${"a" * 64}  other\n"
      assert(Sha256SumChecksumFile.find(content, "tool") == Sha256SumChecksumFile.Lookup.NotFound)

    test("sha256sum lookup unescapes GNU backslash-prefixed filenames"):
      val content = s"\\${"a" * 64}  weird\\\\name\n"
      assert(Sha256SumChecksumFile.find(content, "weird\\name") ==
        Sha256SumChecksumFile.Lookup.Found(digest("a" * 64)))

    test("versions output flags unavailable GitHub release metadata without failing"):
      val tempRoot = tempDirectory("core-github-unavailable")
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

      assert(result.status == InstallerRunStatus.Succeeded)
      // A failed latest-release fetch renders "?" so it is distinguishable from a genuine "-".
      assert(versionSummaryRowExists(result.lines, "jujutsu", "0.40.0", "?"))
      assert(!result.lines.exists(_.contains("HTTP 403")))

    test("lock writes pinned http-text and dynamic source metadata without apply state"):
      val tempRoot = tempDirectory("core-lock")
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

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(result.lines.exists(_.contains("wrote lock file")))
      assert(lock.schemaVersion == LockFile.schemaVersion)
      assert(lock.profileName == "lock-profile")
      assert(lock.manifestFingerprint.nonEmpty)
      assert(lock.tools.map(_.name.value) == Vector("alpha", "beta", "gamma"))
      assert(tools(toolName("alpha")).resolvedVersion.contains("1.0.0"))
      assert(tools(toolName("alpha")).versionProvenance.isEmpty)
      assert(
        tools(toolName("alpha")).downloadProvenance.finalUrl ==
          "https://example.invalid/alpha-1.0.0"
      )
      assert(tools(toolName("alpha")).sizeBytes.contains(11L))
      assert(tools(toolName("alpha")).checksum.contains(LockFileChecksum("sha256", "a" * 64)))
      assert(!tools(toolName("alpha")).dynamicSource)
      assert(tools(toolName("beta")).resolvedVersion.contains("2.0.0"))
      assert(tools(toolName("beta")).versionProvenance.exists(_.finalUrl ==
        "https://cdn.example.invalid/beta-version"))
      assert(
        tools(toolName("beta")).downloadProvenance.finalUrl ==
          "https://cdn.example.invalid/beta-2.0.0"
      )
      assert(tools(toolName("beta")).sizeBytes.contains(22L))
      assert(!tools(toolName("beta")).dynamicSource)
      assert(tools(toolName("gamma")).resolvedVersion.isEmpty)
      assert(tools(toolName("gamma")).versionProvenance.isEmpty)
      assert(tools(toolName("gamma")).sizeBytes.isEmpty)
      assert(tools(toolName("gamma")).checksum.exists(_.value == "c" * 64))
      assert(tools(toolName("gamma")).dynamicSource)
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))
      assert(!Files.exists(tempRoot.resolve("apps")))

    test("lock building stops metadata requests after the first failure"):
      val tempRoot = tempDirectory("core-lock-short-circuit")
      val config   = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath = tempRoot.resolve("failed.lock.json")
      val metadata = RecordingBinaryMetadataClient: url =>
        if url.endsWith("alpha-1.0.0") then Left(BinaryMetadataError(url, "metadata unavailable"))
        else
          Right(BinaryMetadata(
            Some(1L),
            UrlProvenance.direct(url),
            Some(Sha256Digest.trusted("a" * 64))
          ))
      val service = BinaryInstallerService.resolving(
        LockHttpTextClient("2.0.0", betaVersionProvenance),
        DirectBinaryInstaller(
          FakeBinaryDownloadClient.failure("lock must not download"),
          InstallFileSystem.nio
        ),
        ApplyStateStore.nio(tempRoot),
        metadata,
        LockFileStore.nio
      )

      val result = service.lock(applyOptions(config), LockOptions(lockPath.toString))

      assert(result.status == InstallerRunStatus.Failed)
      assert(metadata.urls == Vector("https://example.invalid/alpha-1.0.0"))
      assert(!Files.exists(lockPath))

    test("discovered checksum succeeds and is visible in plan versions and lock output"):
      val tempRoot        = tempDirectory("core-checksum-discovered")
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

      assert(planResult.status == InstallerRunStatus.Succeeded)
      assert(planResult.lines.exists(_.contains(s"checksum: sha256 $artifactHash (discovered")))
      assert(planResult.lines.exists(_.contains(checksumFileUrl)))
      assert(versionsResult.status == InstallerRunStatus.Succeeded)
      // alpha's download is not a GitHub release URL, so its newer-version status is unchecked.
      assert(versionSummaryRowExists(versionsResult.lines, "alpha", "1.0.0", "?"))
      assert(!versionsResult.lines.exists(_.contains("checksums:")))
      assert(applyResult.status == InstallerRunStatus.Succeeded)
      assert(Files.readString(tempRoot.resolve("apps/alpha/bin/alpha")) == "alpha-binary")
      assert(lockResult.status == InstallerRunStatus.Succeeded)
      assert(lockResult.lines.exists(_.contains(
        "checksums: configured 0, discovered 1, inspected 0, missing 0"
      )))
      // Matching the case binds url and file together, so the three facts cannot disagree the way
      // a string tag plus two independently-nullable fields could.
      assert(lock.tools.head.checksum.map(_.source).exists:
        case LockedChecksumSource.Discovered(url, file, _) => url == checksumFileUrl &&
          file == "alpha-1.0.0.tar.gz"
        case _ => false)

    test("ambiguous discovered checksum fails resolution with a colliding-path diagnostic"):
      val tempRoot        = tempDirectory("core-checksum-ambiguous")
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

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.contains(
        "checksum discovery found multiple sha256sum entries matching 'alpha-1.0.0.tar.gz'"
      )))
      assert(result.lines.exists(line =>
        line.contains("linux/alpha-1.0.0.tar.gz") && line.contains("darwin/alpha-1.0.0.tar.gz")
      ))

    test("missing checksum file fails resolution with a typed diagnostic"):
      val tempRoot        = tempDirectory("core-checksum-missing-file")
      val checksumFileUrl = "https://example.invalid/releases/1.0.0/SHA256SUMS"
      val config          = writeConfig(tempRoot, checksumDiscoveryYaml(tempRoot, checksumFileUrl))
      val service         = BinaryInstallerService.resolving(
        RoutingHttpTextClient(Map(
          checksumFileUrl -> Left(HttpTextError(checksumFileUrl, "HTTP 404"))
        ))
      )

      val result = service.plan(applyOptions(config))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.contains("checksum discovery failed: HTTP 404")))
      assert(result.lines.exists(_.contains("spec.plan[0].spec.download.checksum.discover.url")))

    test("mismatched discovered checksum fails before replacement"):
      val tempRoot        = tempDirectory("core-checksum-discovered-mismatch")
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

      assert(result.status == InstallerRunStatus.Failed)
      assert(output.contains("checksum: sha256 expected"))
      assert(output.contains("checksum source: discovered"))
      assert(output.contains(checksumFileUrl))
      assert(Files.readString(existingFile) == "existing")

    test("locked plan validates lock and renders locked provenance without writes"):
      val tempRoot = tempDirectory("core-locked-plan")
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

      assert(result.status == InstallerRunStatus.Succeeded)
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

    test("locked apply rejects a concrete version marked dynamic before side effects"):
      val tempRoot = tempDirectory("core-locked-concrete-dynamic")
      val config   = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath = tempRoot.resolve("binstaller.lock.json")
      val current  = currentLockFile(config, dynamicSize = Some(33L))
      writeLock(
        lockPath,
        current.copy(tools = current.tools.map:
          case tool if tool.name.value == "alpha" => tool.copy(dynamicSource = true)
          case tool                               => tool)
      )
      val delegate = lockMetadataClient(Some(33L))
      val metadata = RecordingBinaryMetadataClient(delegate.metadata)
      val service  = BinaryInstallerService.resolving(
        LockHttpTextClient("2.0.0", betaVersionProvenance),
        DirectBinaryInstaller(
          FakeBinaryDownloadClient.failure("locked apply must not download"),
          InstallFileSystem.nio
        ),
        ApplyStateStore.nio(tempRoot),
        metadata,
        LockFileStore.nio
      )

      val result = service.apply(applyOptions(config).copy(
        lockPath = lockPath.toString,
        lockedApply = LockedApplyMode.Enabled
      ))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.contains("unexpected concrete version fields")))
      assert(metadata.urls.isEmpty)
      assert(!Files.exists(tempRoot.resolve("apps")))
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))

    test("locked apply rejects dynamic version concrete fields before side effects"):
      val tempRoot = tempDirectory("core-locked-dynamic-fields")
      val config   = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath = tempRoot.resolve("binstaller.lock.json")
      val current  = currentLockFile(config, dynamicSize = Some(33L))
      writeLock(
        lockPath,
        current.copy(tools = current.tools.map:
          case tool if tool.name.value == "gamma" => tool.copy(resolvedVersion = Some("latest"))
          case tool                               => tool)
      )
      val delegate = lockMetadataClient(Some(33L))
      val metadata = RecordingBinaryMetadataClient(delegate.metadata)
      val service  = BinaryInstallerService.resolving(
        LockHttpTextClient("2.0.0", betaVersionProvenance),
        DirectBinaryInstaller(
          FakeBinaryDownloadClient.failure("locked apply must not download"),
          InstallFileSystem.nio
        ),
        ApplyStateStore.nio(tempRoot),
        metadata,
        LockFileStore.nio
      )

      val result = service.apply(applyOptions(config).copy(
        lockPath = lockPath.toString,
        lockedApply = LockedApplyMode.Enabled
      ))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.contains("unexpected concrete version fields")))
      assert(metadata.urls.isEmpty)
      assert(!Files.exists(tempRoot.resolve("apps")))
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))

    test("locked apply rejects stale manifest fingerprint before install"):
      val tempRoot  = tempDirectory("core-locked-stale")
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

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.contains("manifest fingerprint changed")))
      assert(!Files.exists(tempRoot.resolve("apps/alpha")))
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))

    test("locked apply rejects a malformed locked checksum even when the manifest pins one"):
      // The format check used to run only for tools with no manifest checksum, so a corrupt digest
      // on a pinned tool fell through to a comparison against an unvalidated string.
      val tempRoot  = tempDirectory("core-locked-bad-digest")
      val config    = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath  = tempRoot.resolve("binstaller.lock.json")
      val current   = currentLockFile(config, dynamicSize = Some(33L))
      val corrupted = current.copy(tools = current.tools.map: tool =>
        if tool.name.value == "alpha" then
          tool.copy(checksum = tool.checksum.map(_.copy(value = "not-a-valid-sha256")))
        else tool)
      writeLock(lockPath, corrupted)
      val service = lockedApplyService(tempRoot, dynamicSize = Some(33L))

      val result = service.apply(
        applyOptions(config).copy(
          lockPath = lockPath.toString,
          lockedApply = LockedApplyMode.Enabled
        )
      )

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.contains("invalid locked checksum")))
      assert(!Files.exists(tempRoot.resolve("apps/alpha")))

    test("locked apply rejects download provenance drift before install"):
      val tempRoot  = tempDirectory("core-locked-url-drift")
      val config    = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath  = tempRoot.resolve("binstaller.lock.json")
      val staleBeta = currentLockFile(config, dynamicSize = Some(33L)).tools.map:
        case tool if tool.name.value == "beta" =>
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

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.contains("download provenance changed")))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))

    test("locked apply rejects missing dynamic lock data"):
      val tempRoot = tempDirectory("core-locked-dynamic-missing")
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

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.contains("no locked sha256 digest")))
      assert(!Files.exists(tempRoot.resolve("apps")))
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))

    test("locked apply rejects missing lock before install"):
      val tempRoot   = tempDirectory("core-locked-missing")
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

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.contains("is missing")))
      assert(!Files.exists(installDir))

    test("nested unknown lock checksum source is classified as a decode failure"):
      val tempRoot = tempDirectory("core-lock-decode")
      val config   = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath = tempRoot.resolve("corrupt.lock.json")
      val encoded  = write(currentLockFile(config, dynamicSize = Some(33L)), indent = 2)
      val corrupt  = encoded.replace("\"configured\"", "\"unknown-checksum-source\"")
      assert(corrupt != encoded)
      Files.writeString(lockPath, corrupt)

      LockFileStore.nio.load(lockPath) match
        case Left(LockFileError.DecodeFailed(_, message)) =>
          assert(message.contains("unknown-checksum-source"))
        case other => abort(s"expected lock decode failure, got $other")

    test("locked apply verifies digest against bytes from the installation GET"):
      val tempRoot     = tempDirectory("core-locked-get-digest")
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
            toolName("alpha"),
            Some("1.0.0"),
            None,
            UrlProvenance.direct(url),
            Some(expected.length.toLong),
            Some(LockFileChecksum.inspected("sha256", expectedHash)),
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

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.contains("checksum: sha256 expected")))
      assert(!Files.exists(installDir))

    test("checksum mismatch diagnostics redact discovered checksum source"):
      val secret = "secret-token-value"
      val plan   = ResolvedPlan(
        ResolvedPolicy.restricted("/tmp/apps"),
        Vector(directTool(Path.of("/tmp/apps/alpha")).copy(download =
          ResolvedDownload(
            url = "https://example.invalid/alpha",
            filename = "alpha",
            checksum = Some(ResolvedChecksum(
              ChecksumAlgorithm.Sha256,
              digest("0" * 64),
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

      assert(result.status == InstallerRunStatus.Failed)
      assert(output.contains(
        "checksum source: discovered from https://example.invalid/<redacted>/SHA256SUMS"
      ))
      assert(!output.contains(secret))
