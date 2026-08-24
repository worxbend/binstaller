package binstaller.cli

import binstaller.core.{
  BinaryInstallerService,
  InstallerEventObserver,
  InstallerOptions,
  InstallerResult,
  InstallerRunStatus,
  LockOptions
}

/** A `BinaryInstallerService` that renders a recognisable line per command and never touches the
 *  filesystem or the network.
 *
 *  CLI tests exist to check argument parsing, flag plumbing, exit codes and output shape. Wiring
 *  them to the real resolving service would make them depend on manifests, downloads and install
 *  directories, so they use this stub instead and assert on the line it produces.
 *
 *  It deliberately lives in the CLI test sources: a service that reports success without
 *  installing anything must not be reachable from production code.
 */
private[cli] object StubBinaryInstallerService extends BinaryInstallerService:

  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult = stubResult("plan", options)

  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult = stubResult("apply", options)

  def versions(options: InstallerOptions): InstallerResult = stubResult("versions", options)

  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult =
    stubResult("lock", options)

  private def stubResult(command: String, options: InstallerOptions): InstallerResult =
    InstallerResult(Vector(s"binstaller $command placeholder for ${options.configPath}"), InstallerRunStatus.Succeeded)
