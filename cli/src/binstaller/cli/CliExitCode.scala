package binstaller.cli

import binstaller.core.InstallerRunStatus

import picocli.CommandLine

/**
 * The one place a run outcome becomes a process exit code.
 *
 * Core reports what happened as an [[InstallerRunStatus]] and takes no view on POSIX status
 * numbers, because those are a property of being delivered as a command-line program rather than of
 * installing anything. Keeping the mapping here means the numbers a script greps for are defined
 * once, next to the other picocli codes, instead of being chosen at each place in the installer
 * that happens to know a run failed.
 */
private[cli] object CliExitCode:

  /** Process exit code for a finished run: 0 on success, 1 on failure. */
  def of(status: InstallerRunStatus): Int = status match
    case InstallerRunStatus.Succeeded => CommandLine.ExitCode.OK
    case InstallerRunStatus.Failed    => CommandLine.ExitCode.SOFTWARE
