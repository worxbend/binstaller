package binstaller.core

import binstaller.config.PlanEntry

/** A lock file ready to save, and how many of its entries came from the file it replaces. */
private[core] final case class MergedLockFile(lockFile: LockFile, keptEntries: Int)

/**
 * Folds a lock written for part of a manifest into the lock already at its path.
 *
 * `lock --only`, and `lock` on a host whose `when` selectors leave tools out, lock a subset.
 * Writing that subset over a full lock would drop the reviewed pins of every other tool, and the
 * next `--locked` run without the same selection would refuse.
 */
private[core] object LockFileMerge:

  /**
   * Keep the existing entries of tools this run did not lock, in manifest order.
   *
   * Only a lock for the same profile and manifest is merged. Any other existing file describes
   * something else and is replaced, as before.
   */
  def merge(
      written: LockFile,
      existing: Option[LockFile],
      plan: Vector[PlanEntry]
  ): MergedLockFile = existing.filter(sameManifest(written, _)) match
    case None           => MergedLockFile(written, 0)
    case Some(previous) =>
      val writtenNames = written.tools.map(_.name).toSet
      val kept         = previous.tools.filterNot(tool => writtenNames(tool.name))
      val position     = plan.map(_.name).zipWithIndex.toMap
      // A name the manifest lacks only appears in a hand-edited file; it sorts last, stably.
      val tools = (written.tools ++ kept).sortBy(tool => position.getOrElse(tool.name, plan.size))
      MergedLockFile(written.copy(tools = tools), kept.size)

  private def sameManifest(written: LockFile, previous: LockFile): Boolean =
    previous.schemaVersion == written.schemaVersion &&
      previous.profileName == written.profileName &&
      previous.manifestFingerprint == written.manifestFingerprint
