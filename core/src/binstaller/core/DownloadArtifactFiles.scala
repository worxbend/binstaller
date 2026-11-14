package binstaller.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.Using

/**
 * Allocates the temporary files that hold downloaded artifacts.
 *
 * A signal exit skips every `finally`, so the artifact of an in-flight download would outlive the
 * run, up to the download size cap each. Two reclaim paths cover that. Each new file is registered
 * for deletion at exit, which reaches Ctrl-C and SIGTERM. Leftovers of a killed or crashed run are
 * swept before the next file is created.
 */
private[core] final class DownloadArtifactFiles(
    val directory: Path,
    val deleteAtExit: Path => Unit,
    now: () => Instant
):

  def create(): Path =
    sweepStale()
    val artifact =
      Files.createTempFile(directory, DownloadArtifactFiles.prefix, DownloadArtifactFiles.suffix)
    deleteAtExit(artifact)
    artifact

  // Best effort: a sweep that cannot list or delete must not fail the download it precedes.
  private def sweepStale(): Unit =
    val staleBefore = now().minus(DownloadArtifactFiles.staleAfter)
    val _           = Try:
      Using.resource(Files.newDirectoryStream(directory, DownloadArtifactFiles.glob)): artifacts =>
        artifacts.iterator().asScala.filter(isOlderThan(_, staleBefore)).foreach: stale =>
          val _ = Try(Files.deleteIfExists(stale))

  private def isOlderThan(artifact: Path, staleBefore: Instant): Boolean =
    Try(Files.getLastModifiedTime(artifact).toInstant).toOption.exists(_.isBefore(staleBefore))

private[core] object DownloadArtifactFiles:
  private val prefix = "binstaller-download-"
  private val suffix = ".artifact"
  private val glob   = s"$prefix*$suffix"

  // A live artifact is written for at most the body timeout and staged within minutes, so a file
  // untouched for this long belongs to no running download. Younger files are never swept, because
  // several binstaller processes can download at once.
  private val staleAfter: Duration = Duration.ofHours(1)

  /** Registers an artifact for deletion when the JVM exits, the reclaim path for Ctrl-C. */
  val exitDeletion: Path => Unit = artifact => artifact.toFile.deleteOnExit()

  /** Artifact files in the system temp directory, deleted when the process exits. */
  def systemTemp: DownloadArtifactFiles = DownloadArtifactFiles(
    Path.of(System.getProperty("java.io.tmpdir")),
    exitDeletion,
    () => Instant.now()
  )
