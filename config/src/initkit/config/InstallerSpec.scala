package initkit.config

enum InstallerSpec:
  case BinaryDownloads(items: Vector[BinaryDownloadItem])
  case ShellScripts(items: Vector[ShellScriptItem])
  case NerdFonts(tool: ToolInvocation, config: GeneratedConfig, preview: Option[PreviewInvocation])
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
    archive: Option[Archive]
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
  case TarGz

final case class ShellScriptItem(
    name: String,
    url: String,
    shell: String,
    args: Vector[String],
    creates: Option[String]
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
    when: Option[Condition]
)
