package binstaller.core

import scala.util.Using

/** Typed embedded API for plan resolution and lock creation. */
trait BinaryInstaller extends BinaryInstallerService:

  /** Resolve and select a plan without rendering it or performing writes. */
  def plan(request: PlanRequest): Either[ResolvePlanError, ResolvedPlan]

  /** Resolve, build, and save a lock file, returning the exact saved values. */
  def lock(request: LockRequest): Either[LockCommandError, LockReport]

/** Resource-owning production entry point for the typed embedded API. */
object BinaryInstaller:

  /**
   * Use a production installer backed by one shared JDK HTTP client.
   *
   * The installer and its HTTP runtime remain valid for the complete synchronous callback. The
   * client is closed after the callback returns or throws, and the callback result or failure is
   * propagated unchanged.
   */
  def useDefault[A](use: BinaryInstaller => A): A =
    Using.resource(RuntimeHttpClient.create()): httpClient =>
      val installer = DirectBinaryInstaller(
        JdkBinaryDownloadClient(httpClient),
        InstallFileSystem.nio,
        CommandExecutor.process
      )
      use(BinaryInstallerService.resolving(
        JdkHttpTextClient(httpClient),
        installer,
        metadataClient = JdkBinaryMetadataClient(httpClient)
      ))
