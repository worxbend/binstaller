package binstaller.core

/**
 * One row of the `versions` table, as data rather than as a padded string.
 *
 * Core also renders these rows into `InstallerResult.lines` so `versions` stays script-friendly,
 * but a renderer that wants to style the table gets the rows themselves. Recovering them by
 * splitting the rendered text back apart cannot be done reliably: the column separator is two
 * spaces, so any package name or version containing two consecutive spaces splits into the wrong
 * columns, and the `-` / `?` sentinels become indistinguishable from a tag that happens to be
 * spelled that way.
 */
final case class VersionSummaryRow(
    packageName: String,
    version: String,
    newer: NewerVersionStatus
)

/** Rendering helpers for the versions table. */
object VersionSummaryRow:

  /** Column widths of the versions table, computed over every rendered row, header included. */
  final case class Layout(packageWidth: Int, versionWidth: Int)

  /** Compute the widths both core's plain table and the CLI's styled one align their columns to. */
  def layoutFor(rows: Vector[VersionSummaryRow]): Layout = Layout(
    rows.map(_.packageName.length).maxOption.getOrElse(0),
    rows.map(_.version.length).maxOption.getOrElse(0)
  )

/** Whether a newer upstream release is known for a tool. */
enum NewerVersionStatus:

  /** The resolved version is the latest known release. */
  case UpToDate

  /** No upstream release information could be obtained. */
  case Unknown

  /** A newer release exists upstream, published under `tag`. */
  case Available(tag: String)

/** Rendering helpers for newer-version status. */
object NewerVersionStatus:

  /** The `newer version` column text: a tag, `-` for up to date, or `?` when unknown. */
  def render(status: NewerVersionStatus): String = status match
    case NewerVersionStatus.UpToDate       => "-"
    case NewerVersionStatus.Unknown        => "?"
    case NewerVersionStatus.Available(tag) => tag
