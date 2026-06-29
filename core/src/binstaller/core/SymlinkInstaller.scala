package binstaller.core

import binstaller.config.AllowSudoSymlinks
import binstaller.config.SymlinkPrivilege

import java.nio.file.Files
import java.nio.file.Path
import scala.util.Failure
import scala.util.Success
import scala.util.Try

private[core] object SymlinkInstaller:

  def create(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      commandExecutor: CommandExecutor
  ): Either[ToolInstallError, Unit] =
    val writes = tool.symlinks.map: symlink =>
      symlink.privilege match
        case SymlinkPrivilege.User => createLocalSymlink(tool, symlink)
        case SymlinkPrivilege.Sudo => createSudoSymlink(policy, tool, symlink, commandExecutor)
    writes.collectFirst:
      case Left(error) => error
    match
      case Some(error) => Left(error)
      case None        => Right(())

  private def createLocalSymlink(
      tool: ResolvedTool,
      symlink: ResolvedSymlink
  ): Either[ToolInstallError, Unit] =
    for
      path   <- resolveInsideInstall(tool, symlink.path)
      target <- resolveSymlinkTarget(tool, symlink)
      _      <- Try:
        Option(path.getParent).foreach(parent => Files.createDirectories(parent))
        val _ = Files.deleteIfExists(path)
        Files.createSymbolicLink(path, target)
      match
        case Success(_)     => Right(())
        case Failure(error) => Left(ToolInstallError.SymlinkFailed(
            tool.name,
            path.toString,
            target.toString,
            error.getMessage
          ))
    yield ()

  private def createSudoSymlink(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      symlink: ResolvedSymlink,
      commandExecutor: CommandExecutor
  ): Either[ToolInstallError, Unit] = policy.allowSudoSymlinks match
    case AllowSudoSymlinks.Disabled => Left(ToolInstallError.SudoSymlinkNotAllowed(tool.name))
    case AllowSudoSymlinks.Enabled  =>
      val path = Path.of(symlink.path)
      // Privileged writes must name an absolute destination. Relative sudo paths would depend on
      // process cwd and make dry-run output misleading.
      if !path.isAbsolute then
        Left(
          ToolInstallError.SymlinkFailed(
            tool.name,
            symlink.path,
            symlink.target,
            "sudo symlink path must be absolute"
          )
        )
      else
        resolveSymlinkTarget(tool, symlink).flatMap: target =>
          // Sudo is reached only through this fixed argv shape; manifest values are data args, not
          // shell text.
          val spec = CommandSpec(
            Vector("sudo", "ln", "-sfn", target.toString, path.toString),
            Path.of(tool.installDir).toAbsolutePath.normalize(),
            CommandEnvironment.baseline
          )
          commandExecutor.run(spec).left.map: error =>
            ToolInstallError.SymlinkFailed(
              tool.name,
              path.toString,
              target.toString,
              CommandFailureDetails.render(error)
            )

  private def resolveSymlinkTarget(
      tool: ResolvedTool,
      symlink: ResolvedSymlink
  ): Either[ToolInstallError, Path] =
    val installDir = Path.of(tool.installDir).toAbsolutePath.normalize()
    val rawTarget  = Path.of(symlink.target)
    val target     =
      if rawTarget.isAbsolute then rawTarget.toAbsolutePath.normalize()
      else installDir.resolve(rawTarget).normalize()
    // Symlink targets are confined to the installed tool tree so a manifest cannot expose arbitrary
    // user files through local or sudo links.
    if target.startsWith(installDir) then Right(target)
    else
      Left(
        ToolInstallError.SymlinkFailed(
          tool.name,
          symlink.path,
          symlink.target,
          "symlink target must resolve inside installDir"
        )
      )

  private def resolveInsideInstall(
      tool: ResolvedTool,
      relative: String
  ): Either[ToolInstallError, Path] =
    val input      = Path.of(relative)
    val installDir = Path.of(tool.installDir).toAbsolutePath.normalize()
    if input.isAbsolute then
      Left(ToolInstallError.StagingFailed(tool.name, s"path must be relative: $relative"))
    else
      val resolved = installDir.resolve(input).normalize()
      if resolved.startsWith(installDir) then Right(resolved)
      else Left(ToolInstallError.StagingFailed(tool.name, s"path escapes installDir: $relative"))
