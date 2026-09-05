package binstaller.core

import java.nio.file.Path

/** Inputs needed to resolve and write reproducible lock metadata. */
final case class LockRequest(
    profile: ProfileInput,
    outputPath: Path,
    selection: ToolSelection = ToolSelection.all
)
