package binstaller.core

import binstaller.config.Diagnostics
import binstaller.config.ExecutableMode

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using

/** POSIX executable mode parsed from a validated four-digit octal string. */
final case class ExecutableInstallMode(octal: String, numeric: Int):

  /** Convert numeric bits into POSIX file permissions. */
  def permissions: Set[PosixFilePermission] =
    val ownerRead    = permission(PosixFilePermission.OWNER_READ, 0x100)
    val ownerWrite   = permission(PosixFilePermission.OWNER_WRITE, 0x080)
    val ownerExecute = permission(PosixFilePermission.OWNER_EXECUTE, 0x040)
    val groupRead    = permission(PosixFilePermission.GROUP_READ, 0x020)
    val groupWrite   = permission(PosixFilePermission.GROUP_WRITE, 0x010)
    val groupExecute = permission(PosixFilePermission.GROUP_EXECUTE, 0x008)
    val otherRead    = permission(PosixFilePermission.OTHERS_READ, 0x004)
    val otherWrite   = permission(PosixFilePermission.OTHERS_WRITE, 0x002)
    val otherExecute = permission(PosixFilePermission.OTHERS_EXECUTE, 0x001)

    Vector(
      ownerRead,
      ownerWrite,
      ownerExecute,
      groupRead,
      groupWrite,
      groupExecute,
      otherRead,
      otherWrite,
      otherExecute
    ).flatten.toSet

  private def permission(
      permission: PosixFilePermission,
      bit: Int
  ): Option[PosixFilePermission] = if (numeric & bit) == bit then Some(permission) else None

/** Executable mode constructors. */
object ExecutableInstallMode:
  /** Default mode for installed executables. */
  val default: ExecutableInstallMode = fromOctal("0755")

  /** Convert an optional manifest mode into an executable install mode. */
  def fromConfig(mode: Option[ExecutableMode]): ExecutableInstallMode = mode match
    case Some(value) => fromOctal(value.value)
    case None        => default

  /** Parse a validated four-digit octal mode. */
  def fromOctal(value: String): ExecutableInstallMode =
    ExecutableInstallMode(value, Integer.parseInt(value, 8))

/** Request to apply a POSIX mode to an executable inside a staged install. */
final case class ExecutableModeRequest(path: String, mode: ExecutableInstallMode)

/** Staging directory paired with the final install directory it will replace. */
final case class StagedInstall(stagingDir: Path, installDir: Path)

/** Expected filesystem failure while staging or replacing an install. */
enum InstallFileSystemError:
  case StagingFailed(message: String)
  case ModeApplicationFailed(path: String, mode: String, message: String)
  case ReplacementFailed(message: String)

/** Filesystem boundary for staging artifacts before replacing a final install directory. */
trait InstallFileSystem:

  /** Stage a downloaded binary into a temporary install tree, streaming it from `artifact`.
   *
   *  Abstract on purpose. A default that read the artifact into a byte array would let any
   *  implementation pull a download-cap-sized file into the JVM heap simply by not overriding it.
   */
  def stageDirectBinaryFromFile(
      installDir: Path,
      createDirectories: Vector[String],
      executablePath: String,
      artifact: Path
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall]

  /** Stage the manifest's selected archive members into a temporary install tree, streamed. */
  def stageArchiveFromFile(
      installDir: Path,
      createDirectories: Vector[String],
      archive: ResolvedArchive,
      artifact: Path
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall]

  /** Apply requested executable modes inside the staged install tree. */
  def applyExecutableModes(
      stagedInstall: StagedInstall,
      executables: Vector[ExecutableModeRequest]
  ): Either[InstallFileSystemError.ModeApplicationFailed, Unit]

  /** Replace the final install directory with the staged install tree. */
  def replaceInstall(
      stagedInstall: StagedInstall
  ): Either[InstallFileSystemError.ReplacementFailed, Unit]

  /** Discard an unused staged install tree. */
  def discardStaged(stagedInstall: StagedInstall): Unit

/** Filesystem boundary constructors. */
object InstallFileSystem:
  /** NIO-backed filesystem implementation. */
  def nio: InstallFileSystem = NioInstallFileSystem

private[core] object NioInstallFileSystem extends InstallFileSystem:

  /** Every staging entry point shares one lifecycle: create a staging directory next to the final
   *  install, pre-create the requested subdirectories, write the payload, and on ANY failure delete
   *  the staging directory again.
   *
   *  That last step is the reason this is a function rather than a convention. A staging method
   *  that forgets it leaves an orphaned `.<name>.stage-*` directory beside every failed install,
   *  and nothing fails: the error the user sees is correct, the install is correctly not replaced,
   *  and the litter accumulates silently until someone looks at the apps directory.
   */
  private def staged(installDir: Path, createDirectories: Vector[String])(
      writePayload: StagedInstall => Either[InstallFileSystemError.StagingFailed, Unit]
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] =
    val normalizedInstallDir = installDir.toAbsolutePath.normalize()
    createStagingDirectory(normalizedInstallDir).flatMap: stagedInstall =>
      stageCreateDirectories(stagedInstall, createDirectories)
        .flatMap(_ => writePayload(stagedInstall)) match
        case Right(())   => Right(stagedInstall)
        case Left(error) =>
          discardStaged(stagedInstall)
          Left(error)

  override def stageDirectBinaryFromFile(
      installDir: Path,
      createDirectories: Vector[String],
      executablePath: String,
      artifact: Path
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] =
    staged(installDir, createDirectories): stagedInstall =>
      resolveInside(stagedInstall.stagingDir, executablePath)
        .flatMap(path => copyBinary(artifact, path))
        .left.map(InstallFileSystemError.StagingFailed.apply)

  override def stageArchiveFromFile(
      installDir: Path,
      createDirectories: Vector[String],
      archive: ResolvedArchive,
      artifact: Path
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] =
    staged(installDir, createDirectories): stagedInstall =>
      ArchiveExtractor.extractFile(archive, artifact, stagedInstall.stagingDir)
        .left.map(InstallFileSystemError.StagingFailed.apply)

  def applyExecutableModes(
      stagedInstall: StagedInstall,
      executables: Vector[ExecutableModeRequest]
  ): Either[InstallFileSystemError.ModeApplicationFailed, Unit] =
    val errors: Vector[Either[InstallFileSystemError.ModeApplicationFailed, Unit]] =
      executables.map(applyExecutableMode(stagedInstall, _))

    errors.collectFirst:
      case Left(error) => error
    match
      case Some(error) =>
        discardStaged(stagedInstall)
        Left(error)
      case None => Right(())

  def replaceInstall(
      stagedInstall: StagedInstall
  ): Either[InstallFileSystemError.ReplacementFailed, Unit] =
    replaceInstallDirectory(stagedInstall) match
      case Right(())   => Right(())
      case Left(error) =>
        discardStaged(stagedInstall)
        Left(error)

  def discardStaged(stagedInstall: StagedInstall): Unit =
    SafePaths.deleteRecursively(stagedInstall.stagingDir)

  // Temp-dir infixes this filesystem creates next to an install; all are reclaimable orphans.
  private val tempInfixes: Vector[String]  = Vector("stage", "backup", "corrupt")
  private val staleTempThreshold: Duration = Duration.ofHours(1)

  private def installName(installDir: Path): String =
    Option(installDir.getFileName).map(_.toString).getOrElse("install")

  // Reclaim temp dirs left behind by a crashed earlier run of THIS tool. Age-guarded so a
  // concurrently-running install of the same tool (whose temp dirs are fresh) is never swept.
  private def sweepStaleSiblings(parent: Path, name: String): Unit =
    val _ = Try:
      if Files.isDirectory(parent) then
        Using.resource(Files.list(parent)): stream =>
          stream.iterator().asScala.foreach: candidate =>
            val candidateName = Option(candidate.getFileName).map(_.toString).getOrElse("")
            val isTemp = tempInfixes.exists(infix => candidateName.startsWith(s".$name.$infix-"))
            if isTemp && isStaleTemp(candidate) then SafePaths.deleteRecursively(candidate)

  private def isStaleTemp(path: Path): Boolean =
    Try(Files.getLastModifiedTime(path).toInstant)
      .toOption
      .exists(modified => modified.isBefore(Instant.now().minus(staleTempThreshold)))

  private def createStagingDirectory(
      installDir: Path
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] = Try:
    val parent = Option(installDir.getParent).getOrElse(Path.of("").toAbsolutePath.normalize())
    val _      = Files.createDirectories(parent)
    val name   = installName(installDir)
    // Reclaim any stale stage/backup/corrupt orphans from a previously crashed install of this tool.
    sweepStaleSiblings(parent, name)
    // Staging lives next to the final install so the later move can be as atomic as the filesystem
    // allows and avoids cross-filesystem replacement surprises.
    StagedInstall(Files.createTempDirectory(parent, s".$name.stage-"), installDir)
  match
    case Success(stagedInstall) => Right(stagedInstall)
    case Failure(error) =>
      Left(InstallFileSystemError.StagingFailed(Diagnostics.describe(error)))

  private def stageCreateDirectories(
      stagedInstall: StagedInstall,
      createDirectories: Vector[String]
  ): Either[InstallFileSystemError.StagingFailed, Unit] =
    val directoryWrites: Vector[Either[String, Unit]] = createDirectories.map: directory =>
      resolveInside(stagedInstall.stagingDir, directory).flatMap: path =>
        Try(Files.createDirectories(path)) match
          case Success(_)     => Right(())
          case Failure(error) => Left(Diagnostics.describe(error))

    val failures = directoryWrites.flatMap(stagingFailure)

    failures.headOption match
      case Some(error) => Left(error)
      case None        => Right(())

  private def stagingFailure(
      result: Either[String, Unit]
  ): Option[InstallFileSystemError.StagingFailed] = result match
    case Left(message) => Some(InstallFileSystemError.StagingFailed(message))
    case Right(())     => None

  private def applyExecutableMode(
      stagedInstall: StagedInstall,
      executable: ExecutableModeRequest
  ): Either[InstallFileSystemError.ModeApplicationFailed, Unit] =
    val executablePath = stagedInstall.stagingDir.resolve(executable.path).normalize()
    // Mode changes are confined to the staged tree, never the previous live install.
    if !executablePath.startsWith(stagedInstall.stagingDir) then
      Left(
        InstallFileSystemError.ModeApplicationFailed(
          executable.path,
          executable.mode.octal,
          "path escapes staging directory"
        )
      )
    else
      Try(
        Files.setPosixFilePermissions(executablePath, executable.mode.permissions.asJava)
      ) match
        case Success(_)     => Right(())
        case Failure(error) => Left(
            InstallFileSystemError.ModeApplicationFailed(
              executable.path,
              executable.mode.octal,
              Diagnostics.describe(error)
            )
          )

  private def copyBinary(source: Path, target: Path): Either[String, Unit] = Try:
    Option(target.getParent).foreach(Files.createDirectories(_))
    val _ = Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
  match
    case Success(_)     => Right(())
    case Failure(error) => Left(Diagnostics.describe(error))

  private def resolveInside(root: Path, relative: String): Either[String, Path] =
    SafePaths.resolveInside(root, relative)

  private def replaceInstallDirectory(
      stagedInstall: StagedInstall
  ): Either[InstallFileSystemError.ReplacementFailed, Unit] =
    val installDir   = stagedInstall.installDir
    val parent       = Option(installDir.getParent).getOrElse(Path.of("").toAbsolutePath.normalize())
    val backupPrefix = s".${installName(installDir)}.backup-"

    val prepared = Try:
      val backupDir = Files.createTempDirectory(parent, backupPrefix)
      Files.delete(backupDir)
      backupDir

    prepared match
      case Failure(error) =>
        Left(InstallFileSystemError.ReplacementFailed(Diagnostics.describe(error)))
      case Success(backupDir) => replaceWithBackup(stagedInstall, installDir, backupDir)

  private def replaceWithBackup(
      stagedInstall: StagedInstall,
      installDir: Path,
      backupDir: Path
  ): Either[InstallFileSystemError.ReplacementFailed, Unit] =
    val hadExisting = Files.exists(installDir)
    val result      = Try:
      if hadExisting then
        val _ = Files.move(installDir, backupDir, StandardCopyOption.REPLACE_EXISTING)
      val _ = Files.move(stagedInstall.stagingDir, installDir, StandardCopyOption.REPLACE_EXISTING)

    result match
      case Success(_) =>
        SafePaths.deleteRecursively(backupDir)
        Right(())
      case Failure(error) =>
        // If the final move fails after moving the old install aside, attempt to restore it so a
        // failed upgrade does not silently leave the tool missing.
        val restoreError = restoreBackup(installDir, backupDir, hadExisting)
        val message      = restoreError match
          case Some(restore) => s"${Diagnostics.describe(error)}; rollback failed: $restore"
          case None          => Diagnostics.describe(error)
        Left(InstallFileSystemError.ReplacementFailed(message))

  private def restoreBackup(
      installDir: Path,
      backupDir: Path,
      hadExisting: Boolean
  ): Option[String] =
    if !hadExisting || !Files.exists(backupDir) then None
    else
      Try:
        if Files.exists(installDir) then
          // Move the failed partial install aside instead of deleting it, so the backup is never
          // the only surviving copy if the restore move below also fails. The aside is reclaimed
          // by sweepStaleSiblings on the next run even if we crash here.
          val parent = Option(installDir.getParent).getOrElse(Path.of("").toAbsolutePath.normalize())
          val corruptAside = Files.createTempDirectory(parent, s".${installName(installDir)}.corrupt-")
          Files.delete(corruptAside)
          val _ = Files.move(installDir, corruptAside, StandardCopyOption.REPLACE_EXISTING)
          val _ = Files.move(backupDir, installDir, StandardCopyOption.REPLACE_EXISTING)
          SafePaths.deleteRecursively(corruptAside)
        else
          val _ = Files.move(backupDir, installDir, StandardCopyOption.REPLACE_EXISTING)
      match
        case Success(_)     => None
        case Failure(error) => Some(Diagnostics.describe(error))
