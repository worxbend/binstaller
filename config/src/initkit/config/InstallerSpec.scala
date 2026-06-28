package initkit.config

enum InstallerSpec:
  case BinaryDownloads(items: Vector[BinaryDownloadItem])
  case ShellScripts(items: Vector[ShellScriptItem])
  case NerdFonts(tool: ToolInvocation, config: GeneratedConfig, preview: Option[PreviewInvocation])
  case FileWrites(items: Vector[FileWriteItem])

  case DotfilesApply(
      tool: ToolInvocation,
      repository: GitRepository,
      config: DotfilesConfig,
      preview: Option[PreviewInvocation]
  )

  case Interrupt(
      reason: String,
      state: InterruptState,
      instructions: Vector[String],
      exit: InterruptExit
  )

  case Commands(items: Vector[CommandItem])

final case class BinaryDownloadItem(
    name: String,
    url: String,
    destination: String,
    mode: String,
    checksum: Option[Checksum],
    archive: Option[Archive],
    symlinks: Vector[BinarySymlink] = Vector.empty
)

final case class Checksum(
    algorithm: ChecksumAlgorithm,
    value: String
)

enum ChecksumAlgorithm:
  case Sha256, Sha512

final case class Archive(
    archiveType: ArchiveType,
    path: String,
    stripComponents: Option[Int]
)

enum ArchiveType:
  case Zip, TarGz, TarXz

final case class ShellScriptItem(
    name: String,
    url: String,
    shell: String,
    args: Vector[String],
    creates: Option[String],
    env: Vector[EnvironmentEntry] = Vector.empty,
    mode: ShellScriptMode = ShellScriptMode.Unattended,
    download: ShellScriptDownloadMode = ShellScriptDownloadMode.File,
    cleanup: Option[Boolean] = None,
    sudo: Option[Boolean] = None,
    cwd: Option[String] = None,
    timeout: Option[Int] = None,
    allowedExitCodes: Vector[Int] = Vector(0)
)

enum ShellScriptMode:
  case Interactive, Unattended

enum ShellScriptDownloadMode:
  case Stdin, File

final case class EnvironmentEntry(
    name: String,
    value: String,
    sensitive: Option[Boolean]
)

final case class BinarySymlink(
    path: String,
    target: Option[String],
    sudo: Option[Boolean]
)

final case class FileWriteItem(
    name: String,
    path: String,
    content: String,
    sudo: Option[Boolean],
    owner: Option[String],
    group: Option[String],
    mode: Option[String],
    when: Option[Condition]
)

final case class ToolInvocation(
    path: String,
    args: Vector[String]
)

final case class GeneratedConfig(
    path: String,
    create: Option[Boolean],
    content: Option[RawYaml]
)

final case class PreviewInvocation(
    enabled: Option[Boolean],
    args: Vector[String]
)

final case class GitRepository(
    url: String,
    ref: Option[String],
    destination: String,
    update: Option[Boolean]
)

final case class DotfilesConfig(
    path: String,
    sourceUrl: Option[String]
)

final case class InterruptState(
    path: String,
    resumeFrom: Option[InterruptResumeFrom]
)

enum InterruptResumeFrom:
  case Current, Next

final case class InterruptExit(
    code: Option[Int],
    message: Option[String]
)

final case class CommandItem(
    name: String,
    run: String,
    sudo: Option[Boolean],
    when: Option[Condition],
    cwd: Option[String] = None,
    env: Vector[EnvironmentEntry] = Vector.empty,
    creates: Option[String] = None,
    unless: Option[String] = None,
    allowedExitCodes: Vector[Int] = Vector(0),
    confirm: Option[String] = None,
    timeout: Option[Int] = None
)
