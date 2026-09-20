package binstaller.config

/**
 * Display paths shared by the manifest decoder and the profile validator.
 *
 * The loader correlates decode errors with validation errors by exact path string, so a path that
 * both can produce is built here once rather than interpolated in two places.
 */
private[config] object ManifestPath:
  def planEntry(index: Int): String     = s"spec.plan[$index]"
  def planEntryName(index: Int): String = s"${planEntry(index)}.name"
