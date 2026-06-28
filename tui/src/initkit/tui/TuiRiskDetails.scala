package initkit.tui

import initkit.config.*
import initkit.core.*

private[tui] object TuiRiskDetails:

  def forManifest(
      manifest: Manifest,
      selectedNames: Vector[String],
      policy: ExecutionPolicy
  ): Vector[String] = manifest.spec.plan.zipWithIndex
    .filter: (entry, _) =>
      selectedNames.isEmpty || entry.name.exists(selectedNames.contains)
    .flatMap: (entry, index) =>
      val name = entry.name.getOrElse(f"entry-${index + 1}%02d")
      forEntry(entry, index, policy).map(risk => s"risk: $name: $risk")

  def forEntry(
      entry: PlanEntry,
      index: Int,
      policy: ExecutionPolicy
  ): Vector[String] =
    val packageRisks =
      if entry.kind.exists(PackageSpecDecoder.isPackageKind) then
        packageRiskLines(entry, index, policy)
      else Vector.empty
    val installerRisks =
      if entry.kind.exists(InstallerSpecDecoder.isInstallerKind) then
        installerRiskLines(entry, index, policy)
      else Vector.empty

    (packageRisks ++ installerRisks).distinct

  def sourceSetupRiskLines(sourceSetup: SourceSetupPlan): Vector[String] =
    sourceSetup.operations.flatMap:
      case SourceSetupOperation.RunCommand(label, command) if command.sudo == SudoMode.Required =>
        Vector(s"risk: source setup sudo command: $label")
      case SourceSetupOperation.RunCommand(_, _)                   => Vector.empty
      case SourceSetupOperation.WriteFile(label, path, _, _, sudo) =>
        val sudoRisk     = Option.when(sudo)(s"risk: source setup sudo file write: $label -> $path")
        val rootPathRisk = Option.when(
          isRootPath(path.toString)
        )(s"risk: source setup root path write: $label -> $path")

        sudoRisk.toVector ++ rootPathRisk.toVector

  private def packageRiskLines(
      entry: PlanEntry,
      index: Int,
      policy: ExecutionPolicy
  ): Vector[String] = PackageSpecDecoder.decode(entry, index).toOption.toVector.flatMap:
    case PackageSpec.Apt(_, _, actions)    => packageManagerRisks("apt", actions, policy)
    case PackageSpec.Pacman(_, _, actions) => packageManagerRisks("pacman", actions, policy)
    case PackageSpec.Dnf(_, actions)       => packageManagerRisks("dnf", actions, policy)
    case PackageSpec.Zypper(_, _, actions) => packageManagerRisks("zypper", actions, policy)
    case PackageSpec.Flatpak(_, system, _) =>
      Option.when(system.contains(true))("system flatpak install").toVector
    case PackageSpec.Snap(_) => Vector("system snap install")
    case PackageSpec.Aur(_, _) | PackageSpec.Cargo(_, _) | PackageSpec.Sdkman(_) => Vector.empty

  private def packageManagerRisks(
      manager: String,
      actions: Vector[PackageAction],
      policy: ExecutionPolicy
  ): Vector[String] =
    val sudo     = Option.when(policy.requireSudo)(s"sudo package manager commands: $manager")
    val upgrades = actions
      .filter(action => isPackageUpgradeAction(action.action))
      .map(action => s"package upgrade action: $manager ${action.actionWithArgs}")

    sudo.toVector ++ upgrades

  private def installerRiskLines(
      entry: PlanEntry,
      index: Int,
      policy: ExecutionPolicy
  ): Vector[String] = InstallerSpecDecoder.decode(entry, index).toOption.toVector.flatMap:
    case InstallerSpec.BinaryDownloads(items) => items.flatMap: item =>
        Vector(s"download install: ${item.name} -> ${item.destination}") ++
          Option
            .when(isRootPath(item.destination))(s"root path binary install: ${item.destination}")
            .toVector ++
          item.symlinks
            .filter(_.sudo.contains(true))
            .map(symlink => s"sudo symlink: ${symlink.path}")
    case InstallerSpec.ShellScripts(items) => items.flatMap: item =>
        Vector(s"download shell script: ${item.name} from ${item.url}") ++
          Option.when(item.sudo.contains(true))(s"sudo shell script: ${item.name}").toVector
    case InstallerSpec.NerdFonts(_, config, _) => Option.when(
        config.create.getOrElse(false)
      )(s"generated config write: ${config.path}").toVector
    case InstallerSpec.DotfilesApply(_, repository, config, _) => Vector(
        s"dotfiles repository checkout/update: ${repository.url} -> ${repository.destination}",
        s"dotfiles config write: ${config.path}"
      )
    case InstallerSpec.FileWrites(items)              => items.flatMap(fileWriteRisks)
    case InstallerSpec.Interrupt(reason, state, _, _) =>
      Vector(s"checkpoint interrupts execution: $reason", s"checkpoint state write: ${state.path}")
    case InstallerSpec.Commands(items) => items.flatMap(commandRisks(_, policy))

  private def fileWriteRisks(item: FileWriteItem): Vector[String] =
    val sudo =
      Option.when(item.sudo.contains(true))(s"sudo file write: ${item.name} -> ${item.path}")
    val rootOwned = Option
      .when(item.owner.contains("root") || item.group.contains("root") || isRootPath(item.path))(
        s"root-owned write: ${item.name} -> ${item.path}${ownerText(item)}"
      )

    sudo.toVector ++ rootOwned.toVector

  private def commandRisks(item: CommandItem, policy: ExecutionPolicy): Vector[String] =
    val sudo = Option.when(item.sudo.getOrElse(policy.requireSudo))(
      s"sudo command: ${item.name} -> ${item.run}"
    )
    val service = Option.when(isServiceChange(item.run))(
      s"service change command: ${item.name} -> ${item.run}"
    )
    val shell = Option.when(isShellChange(item.run))(
      s"shell change command: ${item.name} -> ${item.run}"
    )

    sudo.toVector ++ service.toVector ++ shell.toVector

  private def isPackageUpgradeAction(action: String): Boolean =
    Set("upgrade", "dist-upgrade", "sync-upgrade", "syu", "dup", "dup-from", "update")
      .contains(action)

  private def isServiceChange(command: String): Boolean =
    val normalized = command.toLowerCase
    normalized.contains("systemctl ") || normalized.startsWith("service ") ||
    normalized.contains(" service ") || normalized.contains("rc-service ")

  private def isShellChange(command: String): Boolean =
    val normalized = command.toLowerCase
    normalized.contains("chsh") ||
    normalized.contains("usermod -s") ||
    normalized.contains("usermod --shell")

  private def isRootPath(path: String): Boolean =
    val normalized = path.replace('\\', '/')
    normalized == "/etc" ||
    normalized == "/usr" ||
    normalized == "/opt" ||
    normalized == "/var" ||
    normalized == "/lib" ||
    normalized == "/boot" ||
    normalized.startsWith("/etc/") ||
    normalized.startsWith("/usr/") ||
    normalized.startsWith("/opt/") ||
    normalized.startsWith("/var/") ||
    normalized.startsWith("/lib/") ||
    normalized.startsWith("/boot/")

  private def ownerText(item: FileWriteItem): String =
    val owner = item.owner.map(value => s"owner=$value")
    val group = item.group.map(value => s"group=$value")
    (owner.toVector ++ group.toVector) match
      case values if values.nonEmpty => " " + values.mkString(" ")
      case _                         => ""

  extension (action: PackageAction)
    private def actionWithArgs: String = (action.action +: action.args).mkString(" ")
