package binstaller.core

import binstaller.config.ConfigModule
import binstaller.config.Sha256Digest
import utest.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** What `plan` prints, and how plan and apply name a checksum that the lock file pinned. */
object PlanOutputTest extends TestSuite with CoreTestSupport:

  private val inMemoryOptions: InstallerOptions =
    applyOptions(Path.of("/nonexistent/never-read.yaml"))

  private def planService(yaml: String): BinaryInstallerService = BinaryInstallerService.resolving(
    FakeHttpTextClient(""),
    DirectBinaryInstaller(RoutingBinaryDownloadClient.success, InstallFileSystem.nio),
    resolutionOptions = testResolutionOptions,
    profileSource = ProfileSource.yamlText(yaml)
  )

  private def lockedOptions(config: Path, lockPath: Path): InstallerOptions =
    applyOptions(config).copy(lockPath = lockPath.toString, lockedApply = LockedApplyMode.Enabled)

  private val relativeInstallDirYaml: String = """
                                                 |apiVersion: binstaller.io/v1alpha1
                                                 |kind: BinaryDistributionProfile
                                                 |metadata:
                                                 |  name: relative-install
                                                 |spec:
                                                 |  policy:
                                                 |    appsDir: apps
                                                 |    allowSudoSymlinks: true
                                                 |  versions:
                                                 |    alpha: "1.0.0"
                                                 |  plan:
                                                 |    - name: alpha
                                                 |      kind: binary-tool
                                                 |      spec:
                                                 |        versionRef: alpha
                                                 |        installDir: apps/alpha
                                                 |        download:
                                                 |          url: https://example.invalid/alpha
                                                 |          filename: alpha
                                                 |        executables:
                                                 |          - path: bin/alpha
                                                 |        symlinks:
                                                 |          - path: bin/a
                                                 |            target: bin/alpha
                                                 |          - path: /usr/local/bin/alpha
                                                 |            target: bin/alpha
                                                 |            sudo: true
                                                 |""".stripMargin

  val tests: Tests = Tests:
    test("plan header reports the --state path apply would use"):
      val result = planService(shellSyntaxYaml)
        .plan(inMemoryOptions.copy(statePath = Some("run.state.json")))

      assert(result.lines.contains("state file: run.state.json (not created)"))

    test("plan header falls back to the profile state file, then to none"):
      val profileState =
        planService(twoToolYaml(Path.of("/nonexistent/apps-root"), "profile.state.json"))
          .plan(inMemoryOptions)
      val noState = planService(shellSyntaxYaml).plan(inMemoryOptions)

      assert(profileState.lines.contains("state file: profile.state.json (not created)"))
      assert(noState.lines.contains("state file: not configured"))

    test("plan does not print a download file path that apply never writes"):
      val result = planService(shellSyntaxYaml).plan(inMemoryOptions)

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(!result.lines.exists(_.contains("download file")))

    test("plan without a lock calls out a missing checksum"):
      val result = planService(shellSyntaxYaml).plan(inMemoryOptions)

      assert(result.lines.contains(
        "   checksum: missing (not configured) - NO integrity verification will be performed"
      ))

    test("locked plan prints the locked checksum that apply will verify"):
      val tempRoot = tempDirectory("core-locked-plan-checksum")
      val config   = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath = tempRoot.resolve("binstaller.lock.json")
      writeLock(lockPath, currentLockFile(config, dynamicSize = Some(33L)))
      val service = lockedApplyService(tempRoot, dynamicSize = Some(33L))

      val result = service.plan(lockedOptions(config, lockPath))

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(!result.lines.exists(_.contains("NO integrity verification")))
      assert(result.lines.contains(s"   checksum: sha256 ${"a" * 64} (configured in manifest)"))
      assert(result.lines.contains(
        s"   checksum: sha256 ${"b" * 64} (pinned by lock file $lockPath)"
      ))

    test("locked apply names the lock file as the source of a mismatched checksum"):
      val tempRoot     = tempDirectory("core-locked-checksum-source")
      val installDir   = tempRoot.resolve("alpha")
      val config       = writeConfig(tempRoot, directBinaryYaml(installDir))
      val lockPath     = tempRoot.resolve("binstaller.lock.json")
      val expected     = "expected".getBytes(StandardCharsets.UTF_8)
      val expectedHash = sha256(expected)
      val url          = "https://example.invalid/alpha"
      val profile      = ConfigModule.load(config) match
        case Right(value) => value
        case Left(error)  => abort(s"expected valid config, got $error")
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
          RoutingBinaryDownloadClient(
            Map(url -> Right("changed!".getBytes(StandardCharsets.UTF_8)))
          ),
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

      val output = service.apply(lockedOptions(config, lockPath)).lines.mkString("\n")

      assert(output.contains(s"checksum source: pinned by lock file $lockPath"))
      assert(output.contains(
        "suggestion: verify the downloaded artifact before updating the lock file with " +
          "`binstaller lock`"
      ))
      assert(!output.contains("manifest"))
      assert(!Files.exists(installDir))

    test("plan prints symlink commands with relative ends taken from installDir"):
      val result = planService(validPinnedYaml).plan(inMemoryOptions)

      assert(result.lines.contains(
        "     [local] ln -sfn '/home/test/.apps/alpha-1.2.3/bin/alpha' " +
          "'/home/test/.apps/alpha-1.2.3/bin/a'"
      ))

    test("plan prints symlink ends as the manifest wrote them under a relative installDir"):
      val result = planService(relativeInstallDirYaml).plan(inMemoryOptions)

      assert(
        result.lines.contains("     [local] ln -sfn 'apps/alpha/bin/alpha' 'apps/alpha/bin/a'")
      )
      assert(result.lines.contains(
        "     [sudo risk] sudo ln -sfn 'apps/alpha/bin/alpha' '/usr/local/bin/alpha'"
      ))
