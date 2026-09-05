package binstaller.core

import java.nio.file.Path

/** Exact lock metadata saved by a successful typed lock operation. */
final case class LockReport(path: Path, lockFile: LockFile)
