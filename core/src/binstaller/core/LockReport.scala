package binstaller.core

import java.nio.file.Path

/**
 * Exact lock metadata saved by a successful typed lock operation.
 *
 * `keptEntries` counts the entries carried over from a compatible lock file already at `path`, for
 * tools this run did not lock.
 */
final case class LockReport(path: Path, lockFile: LockFile, keptEntries: Int)
