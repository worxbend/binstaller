package binstaller.core

import binstaller.config.SymlinkPrivilege

import java.nio.file.Path

/**
 * The one rule for where the two ends of a manifest symlink point.
 *
 * Plan validation, plan output, apply and the resume check all ask here. Each of them used to carry
 * its own copy, and the copies drifted: the plan accepted and printed a relative sudo link that
 * apply then refused.
 */
private[core] object SymlinkPaths:

  /**
   * Where the link is created: inside the install for a user link, at an absolute path for a sudo
   * link.
   *
   * Here and in [[target]], a `Left` is a message fragment such as "must be absolute". The caller
   * puts its own label in front.
   */
  def linkPath(
      installDir: Path,
      path: String,
      privilege: SymlinkPrivilege
  ): Either[String, Path] = privilege match
    case SymlinkPrivilege.User => SafePaths.resolveInside(installDir, path)
    case SymlinkPrivilege.Sudo =>
      val destination = Path.of(path)
      // A privileged write must name its destination outright. A relative one would depend on the
      // process working directory.
      if destination.isAbsolute then Right(destination) else Left("must be absolute")

  /** What the link points at: an absolute value or one taken from installDir, always inside it. */
  def target(installDir: Path, target: String): Either[String, Path] =
    val installRoot = installDir.toAbsolutePath.normalize()
    // `resolve` returns an absolute value unchanged, which is the rule: an absolute target stands
    // as written, a relative one hangs off the install directory.
    val resolved = installRoot.resolve(target).normalize()
    // Targets stay inside the installed tool tree, so a manifest cannot expose arbitrary user
    // files through a local or sudo link.
    if resolved.startsWith(installRoot) then Right(resolved)
    else Left("must resolve inside installDir")

  /**
   * The two resolved ends of one link must differ.
   *
   * Creating the link deletes whatever is at its path first. A link that is its own target would
   * delete the installed file it was meant to expose and leave a link that loops onto itself.
   */
  def distinctEnds(linkPath: Path, target: Path): Either[String, Unit] =
    if linkPath.toAbsolutePath.normalize() == target.toAbsolutePath.normalize() then
      Left("must not equal its target")
    else Right(())

  /**
   * The text `plan` prints for either end: an absolute value as written, a relative one under
   * installDir.
   *
   * It stays textual on purpose. The plan prints paths as the manifest wrote them, and resolving
   * here would turn a relative installDir into an absolute path on the symlink lines only.
   */
  def planText(installDir: String, value: String): String =
    if Path.of(value).isAbsolute then value else s"${installDir.stripSuffix("/")}/$value"
