package binstaller.core

/**
 * The entry point to binstaller's core. Start here.
 *
 * Everything a caller needs is four commands, each taking an [[InstallerOptions]] describing which
 * manifest to read and how to behave, and each returning an [[InstallerResult]] with the rendered
 * output lines and a typed run status:
 *
 *   - [[plan]] resolves the manifest and renders what would happen. It writes nothing.
 *   - [[apply]] performs the install.
 *   - [[versions]] reports the resolved version of each tool, and a newer one where known.
 *   - [[lock]] resolves the manifest and writes reproducible lock metadata.
 *
 * `plan` and `apply` each have a `WithEvents` variant ([[planWithEvents]], [[applyWithEvents]])
 * taking an [[InstallerEventObserver]]. The plain forms are those variants with a no-op observer.
 * Use a `WithEvents` form to drive progress output: core emits renderer-agnostic lifecycle events
 * and never decides how they are displayed, which is what keeps terminal formatting out of the
 * install logic. [[applyWithProgress]] is a narrower convenience for callers that only care about
 * download progress.
 *
 * Build the production implementation with `BinaryInstallerService.resolving(httpTextClient)`. The
 * other `resolving` overloads exist to substitute one boundary at a time — the installer, the state
 * store, the metadata client, the lock-file store, the host platform — and default to the
 * production wiring for everything a caller does not override.
 */
trait BinaryInstallerService:

  /** Render a script-friendly install plan without events. */
  def plan(options: InstallerOptions): InstallerResult =
    planWithEvents(options, InstallerEventObserver.none)

  /** Render a plan while emitting renderer-agnostic lifecycle events. */
  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult

  /** Apply a plan without progress or lifecycle observers. */
  def apply(options: InstallerOptions): InstallerResult =
    applyWithEvents(options, InstallerEventObserver.none)

  /** Apply a plan while adapting download-only progress observers. */
  def applyWithProgress(
      options: InstallerOptions,
      progressObserver: BinaryDownloadProgressObserver
  ): InstallerResult = applyWithEvents(
    options,
    InstallerEventObserver.fromDownloadProgress(progressObserver)
  )

  /** Apply a plan while emitting renderer-agnostic lifecycle events. */
  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult

  /** Resolve and render the configured version sources. */
  def versions(options: InstallerOptions): InstallerResult

  /** Resolve and write a reproducible lock file without applying tools. */
  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult

/** Constructors for production and test service implementations. */
object BinaryInstallerService:

  /** Create the production resolving service with the default installer and cwd state store. */
  def resolving(httpTextClient: HttpTextClient): BinaryInstaller =
    resolving(httpTextClient, DirectBinaryInstaller.default)

  /**
   * Create a resolving service with explicit resolution inputs, primarily for deterministic
   * host-platform tests.
   */
  def resolving(
      httpTextClient: HttpTextClient,
      resolutionOptions: ResolutionOptions
  ): BinaryInstaller = resolving(
    httpTextClient,
    DirectBinaryInstaller.default,
    resolutionOptions = resolutionOptions
  )

  /** Create the production resolving service with explicit sudo credential handling. */
  def resolving(
      httpTextClient: HttpTextClient,
      sudoCredentials: SudoCredentialProvider
  ): BinaryInstaller = resolving(httpTextClient, DirectBinaryInstaller.default(sudoCredentials))

  /**
   * Create a resolving service with an injected installer and optionally-overridden state, lock
   * metadata, lock-file storage, host-platform, and manifest-source boundaries.
   *
   * Every default reproduces the production wiring, so a caller overrides only the one boundary it
   * needs. `profileSource = ProfileSource.yamlText(...)` in particular lets a test drive a whole
   * command from an in-memory manifest, without a temporary directory.
   */
  def resolving(
      httpTextClient: HttpTextClient,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore = ApplyStateStore.cwd,
      metadataClient: BinaryMetadataClient = BinaryMetadataClient.jdk,
      lockFileStore: LockFileStore = LockFileStore.nio,
      resolutionOptions: ResolutionOptions = ResolutionOptions.fromEnvironment(),
      profileSource: ProfileSource = ProfileSource.yamlFile
  ): BinaryInstaller = ResolvingBinaryInstallerService(
    httpTextClient,
    resolutionOptions,
    installer,
    stateStore,
    metadataClient,
    lockFileStore,
    profileSource
  )
