package binstaller.core

import java.nio.file.Path

/** Source of a profile supplied to the typed installer API. */
enum ProfileInput:

  /** Read and validate a profile from a filesystem path. */
  case File(path: Path)

  /** Parse and validate a profile from YAML already held in memory. */
  case Yaml(text: String)
