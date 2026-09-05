package consumer

import binstaller.config.ConfigModule
import binstaller.config.Sha256Digest
import binstaller.core.*
import utest.*

import java.nio.file.Path

/** Consumer-level coverage for the public embedded API, from outside `binstaller.core`. */
object BinaryInstallerApiTest extends TestSuite:

  private val checksum = "a" * 64

  val tests: Tests = Tests:
    test("inline YAML resolves a selected pinned plan without HTTP"):
      val http    = ThrowingHttpTextClient()
      val service = installer(http)

      val result = service.plan(PlanRequest(
        ProfileInput.Yaml(profileYaml),
        ToolSelection(Vector("beta"), Vector.empty)
      ))

      assert(http.calls == 0)
      result match
        case Right(plan) =>
          assert(plan.tools.map(_.name.value) == Vector("beta"))
          assert(plan.tools.head.version == ResolvedVersion.Concrete("2.0.0"))
        case Left(error) =>
          throw new java.lang.AssertionError(s"expected selected plan, got $error")

    test("typed plan exposes the selection failure unchanged"):
      val result = installer(ThrowingHttpTextClient()).plan(PlanRequest(
        ProfileInput.Yaml(profileYaml),
        ToolSelection(Vector("missing"), Vector.empty)
      ))

      assert(result == Left(ResolvePlanError.SelectionFailed(Vector("unknown tool 'missing'"))))

    test("invalid inline YAML exposes the config-load error unchanged"):
      val yaml     = "metadata: ["
      val expected = ConfigModule.loadString(yaml).left.map(ResolvePlanError.ConfigLoadFailed.apply)
      val result   = installer(ThrowingHttpTextClient()).plan(PlanRequest(ProfileInput.Yaml(yaml)))

      assert(result == expected)

    test("typed lock returns the exact normalized path and stored lock file"):
      val store   = RecordingLockFileStore()
      val target  = Path.of("consumer-api.lock.json")
      val service = installer(ThrowingHttpTextClient(), lockFileStore = store)

      val result = service.lock(LockRequest(ProfileInput.Yaml(profileYaml), target))

      result match
        case Right(report) =>
          assert(report.path == target.toAbsolutePath.normalize())
          assert(store.saved.contains(report.path -> report.lockFile))
          assert(report.lockFile.tools.map(_.name.value) == Vector("alpha", "beta"))
        case Left(error) => throw new java.lang.AssertionError(s"expected lock report, got $error")

    test("malformed legacy state path fails without state writes or downloads"):
      val stateStore = RecordingStateStore()
      val result = installer(ThrowingHttpTextClient(), stateStore = stateStore).apply(legacyOptions(
        statePath = Some("bad\u0000state")
      ))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.startsWith("state path 'bad")))
      assert(stateStore.loadCalls == 0)
      assert(stateStore.saveCalls == 0)

    test("malformed legacy lock output fails before metadata lookup or writes"):
      val metadata = CountingMetadataClient()
      val store    = RecordingLockFileStore()
      val result   = installer(
        ThrowingHttpTextClient(),
        metadataClient = metadata,
        lockFileStore = store
      ).lock(legacyOptions(), LockOptions("bad\u0000lock"))

      assert(result.status == InstallerRunStatus.Failed)
      assert(!result.lines.mkString.contains('\u0000'))
      assert(!result.lines.mkString.contains('\u001b'))
      assert(metadata.calls == 0)
      assert(store.saved.isEmpty)

    test("malformed legacy locked plan and apply fail without lock reads or writes"):
      val store   = RecordingLockFileStore()
      val service = installer(ThrowingHttpTextClient(), lockFileStore = store)
      val options = legacyOptions(lockPath = "bad\u0000lock", lockedApply = LockedApplyMode.Enabled)

      val plan  = service.plan(options)
      val apply = service.apply(options)

      assert(plan.status == InstallerRunStatus.Failed)
      assert(apply.status == InstallerRunStatus.Failed)
      assert(store.loadCalls == 0)
      assert(store.saved.isEmpty)

    test("useDefault returns callback values and propagates callback failures"):
      assert(BinaryInstaller.useDefault(_ => "alive") == "alive")
      val failure =
        try
          BinaryInstaller.useDefault(_ => throw IllegalStateException("callback failed"))
          None
        catch case error: IllegalStateException => Some(error)
      assert(failure.exists(_.getMessage == "callback failed"))

  private def installer(
      httpTextClient: HttpTextClient,
      stateStore: ApplyStateStore = RecordingStateStore(),
      metadataClient: BinaryMetadataClient = CountingMetadataClient(),
      lockFileStore: LockFileStore = RecordingLockFileStore(),
      profileSource: ProfileSource = ProfileSource.yamlText(profileYaml)
  ): BinaryInstaller = BinaryInstallerService.resolving(
    httpTextClient,
    DirectBinaryInstaller(NoDownloadClient(), InstallFileSystem.nio),
    stateStore,
    metadataClient,
    lockFileStore,
    profileSource = profileSource
  )

  private def legacyOptions(
      statePath: Option[String] = None,
      lockPath: String = LockOptions.defaultOutputPath,
      lockedApply: LockedApplyMode = LockedApplyMode.Disabled
  ): InstallerOptions = InstallerOptions(
    configPath = "ignored-by-inline-source.yaml",
    statePath = statePath,
    resetState = ResetState.Disabled,
    verboseOutput = VerboseOutput.Disabled,
    lockPath = lockPath,
    lockedApply = lockedApply
  )

  private val profileYaml: String = s"""
                                       |apiVersion: binstaller.io/v1alpha1
                                       |kind: BinaryDistributionProfile
                                       |metadata:
                                       |  name: consumer-api
                                       |spec:
                                       |  policy:
                                       |    appsDir: /tmp/binstaller-consumer-api/apps
                                       |  vars: {}
                                       |  versions:
                                       |    alpha: "1.0.0"
                                       |    beta: "2.0.0"
                                       |  plan:
                                       |    - name: alpha
                                       |      kind: binary-tool
                                       |      spec:
                                       |        versionRef: alpha
                                       |        installDir: /tmp/binstaller-consumer-api/apps/alpha
                                       |        download:
                                       |          url: https://example.invalid/alpha-$${version}
                                       |          filename: alpha
                                       |          checksum:
                                       |            algorithm: sha256
                                       |            value: $checksum
                                       |        executables:
                                       |          - path: bin/alpha
                                       |    - name: beta
                                       |      kind: binary-tool
                                       |      spec:
                                       |        versionRef: beta
                                       |        installDir: /tmp/binstaller-consumer-api/apps/beta
                                       |        download:
                                       |          url: https://example.invalid/beta-$${version}
                                       |          filename: beta
                                       |          checksum:
                                       |            algorithm: sha256
                                       |            value: $checksum
                                       |        executables:
                                       |          - path: bin/beta
                                       |""".stripMargin

  private final class ThrowingHttpTextClient extends HttpTextClient:
    var calls: Int = 0

    def getText(url: String): Either[HttpTextError, String] =
      calls += 1
      throw new java.lang.AssertionError(s"unexpected HTTP resolver call for $url")

  private object ThrowingHttpTextClient:
    def apply(): ThrowingHttpTextClient = new ThrowingHttpTextClient

  private final class NoDownloadClient extends BinaryDownloadClient:

    def downloadArtifactWithProvenance(
        url: String,
        progressObserver: BinaryDownloadProgressObserver
    ): Either[BinaryDownloadError, BinaryDownloadArtifact] =
      throw new java.lang.AssertionError(s"unexpected download for $url")

  private object NoDownloadClient:
    def apply(): NoDownloadClient = new NoDownloadClient

  private final class CountingMetadataClient extends BinaryMetadataClient:
    var calls: Int = 0

    def metadata(url: String): Either[BinaryMetadataError, BinaryMetadata] =
      calls += 1
      Right(BinaryMetadata(
        sizeBytes = Some(1L),
        provenance = UrlProvenance.direct(url),
        sha256 = Sha256Digest.fromString(checksum).toOption
      ))

  private object CountingMetadataClient:
    def apply(): CountingMetadataClient = new CountingMetadataClient

  private final class RecordingLockFileStore extends LockFileStore:
    var loadCalls: Int                  = 0
    var saved: Option[(Path, LockFile)] = None

    def load(path: Path): Either[LockFileError, LockFile] =
      loadCalls += 1
      Left(LockFileError.Missing(path))

    def save(path: Path, lockFile: LockFile): Either[LockFileError, Unit] =
      saved = Some(path -> lockFile)
      Right(())

  private object RecordingLockFileStore:
    def apply(): RecordingLockFileStore = new RecordingLockFileStore

  private final class RecordingStateStore extends ApplyStateStore:
    val cwd: Path      = Path.of(".").toAbsolutePath.normalize()
    var loadCalls: Int = 0
    var saveCalls: Int = 0

    def load(path: Path): Either[ApplyStateError, Option[ApplyState]] =
      loadCalls += 1
      Right(None)

    def save(path: Path, state: ApplyState): Either[ApplyStateError, Unit] =
      saveCalls += 1
      Right(())

  private object RecordingStateStore:
    def apply(): RecordingStateStore = new RecordingStateStore
