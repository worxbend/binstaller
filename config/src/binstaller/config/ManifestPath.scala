package binstaller.config

/**
 * Display paths shared by the manifest decoder and the profile validator.
 *
 * The loader correlates decode errors with validation errors by path, so a path that both can
 * produce is built here once rather than interpolated in two places.
 */
private[config] object ManifestPath:
  def planEntry(index: Int): String     = s"spec.plan[$index]"
  def planEntryName(index: Int): String = s"${planEntry(index)}.name"

  /** Whether `path` is `ancestor` or a field below it. `$` is the document root. */
  def isAtOrUnder(path: String, ancestor: String): Boolean = ancestor == "$" || path == ancestor ||
    path.startsWith(s"$ancestor.") || path.startsWith(s"$ancestor[")
