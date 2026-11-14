package binstaller.core

import binstaller.config.PolicyOverride
import binstaller.config.Diagnostics
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
      commandExecutor: CommandExecutor,
      sudoCredentials: SudoCredentialProvider
  ): Either[ToolInstallError, Unit] =
    // `.iterator` is load-bearing, not a style choice: creating a symlink can prompt for a sudo
    // password and shell out to `sudo ln`. A strict `.map` runs every remaining entry after the
    // first failure -- prompting the user again and performing privileged writes -- and then
    // discards all of those results in favour of the first error.
    tool.symlinks.iterator
      .map: symlink =>
        symlink.privilege match
          case SymlinkPrivilege.User => createLocalSymlink(tool, symlink)
          case SymlinkPrivilege.Sudo =>
            createSudoSymlink(policy, tool, symlink, commandExecutor, sudoCredentials)
      .collectFirst:
        case Left(error) => error
      .toLeft(())

  private def createLocalSymlink(
      tool: ResolvedTool,
      symlink: ResolvedSymlink
  ): Either[ToolInstallError, Unit] =
    for
      path   <- linkPath(tool, symlink).left.map(ToolInstallError.StagingFailed(tool.name, _))
      target <- resolveSymlinkTarget(tool, symlink)
      _      <- distinctEnds(tool, symlink, path, target)
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
            Diagnostics.describe(error)
          ))
    yield ()

  private def createSudoSymlink(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      symlink: ResolvedSymlink,
      commandExecutor: CommandExecutor,
      sudoCredentials: SudoCredentialProvider
  ): Either[ToolInstallError, Unit] = policy.allowSudoSymlinks match
    case PolicyOverride.Disabled => Left(ToolInstallError.SudoSymlinkNotAllowed(tool.name))
    case PolicyOverride.Enabled  =>
      for
        path <- linkPath(tool, symlink).left
          .map(message => symlinkFailed(tool, symlink, s"sudo symlink path $message"))
        target <- resolveSymlinkTarget(tool, symlink)
        _      <- distinctEnds(tool, symlink, path, target)
        _      <- runSudoSymlink(tool, path, target, commandExecutor, sudoCredentials)
      yield ()

  private def runSudoSymlink(
      tool: ResolvedTool,
      path: Path,
      target: Path,
      commandExecutor: CommandExecutor,
      sudoCredentials: SudoCredentialProvider
  ): Either[ToolInstallError, Unit] =
    val cwd = Path.of(tool.installDir).toAbsolutePath.normalize()
    commandExecutor.run(cachedCredentialCheck(cwd)) match
      case Right(()) =>
        runSudoCommand(tool, path, target, cachedSudoSpec(cwd, path, target), commandExecutor)
      case Left(_) =>
        val request = SudoCredentialRequest(
          tool.name,
          path.toString,
          target.toString,
          // Defense-in-depth: path/target are already control-char-free (ResolvedPathValidator
          // rejects them post-interpolation), but scrub at the terminal boundary too so this prompt
          // can never be spoofed via escape sequences should an upstream check ever regress.
          RenderSafety.terminalLine(s"create sudo symlink $path -> $target")
        )
        sudoCredentials.requestSudoPassword(request) match
          case Right(password) =>
            try runSudoCommand(
                tool,
                path,
                target,
                passwordSudoSpec(cwd, path, target, password),
                commandExecutor
              )
            finally password.destroy()
          case Left(SudoCredentialError.Canceled) =>
            Left(ToolInstallError.SudoCredentialCanceled(tool.name, path.toString, target.toString))
          case Left(SudoCredentialError.Unavailable(message)) =>
            Left(ToolInstallError.SudoCredentialsUnavailable(
              tool.name,
              path.toString,
              target.toString,
              message
            ))

  private def cachedCredentialCheck(cwd: Path): CommandSpec = CommandSpec(
    Vector("sudo", "-n", "true"),
    cwd,
    CommandEnvironment.baseline
  )

  private def cachedSudoSpec(cwd: Path, path: Path, target: Path): CommandSpec = CommandSpec(
    Vector("sudo", "-n", "ln", "-sfn", target.toString, path.toString),
    cwd,
    CommandEnvironment.baseline
  )

  private def passwordSudoSpec(
      cwd: Path,
      path: Path,
      target: Path,
      password: SudoPassword
  ): CommandSpec = CommandSpec(
    Vector("sudo", "-S", "-p", "", "ln", "-sfn", target.toString, path.toString),
    cwd,
    CommandEnvironment.baseline,
    password.commandInput
  )

  private def runSudoCommand(
      tool: ResolvedTool,
      path: Path,
      target: Path,
      spec: CommandSpec,
      commandExecutor: CommandExecutor
  ): Either[ToolInstallError, Unit] = commandExecutor.run(spec).left.map: error =>
    ToolInstallError.SymlinkFailed(
      tool.name,
      path.toString,
      target.toString,
      CommandFailureDetails.render(error)
    )

  private def linkPath(tool: ResolvedTool, symlink: ResolvedSymlink): Either[String, Path] =
    SymlinkPaths.linkPath(Path.of(tool.installDir), symlink.path, symlink.privilege)

  private def resolveSymlinkTarget(
      tool: ResolvedTool,
      symlink: ResolvedSymlink
  ): Either[ToolInstallError, Path] =
    SymlinkPaths.target(Path.of(tool.installDir), symlink.target).left.map: message =>
      symlinkFailed(tool, symlink, s"symlink target $message")

  // Plan rejects this already; a plan built another way must still not lose the executable.
  private def distinctEnds(
      tool: ResolvedTool,
      symlink: ResolvedSymlink,
      path: Path,
      target: Path
  ): Either[ToolInstallError, Unit] = SymlinkPaths.distinctEnds(path, target).left.map: message =>
    symlinkFailed(tool, symlink, s"symlink path $message")

  private def symlinkFailed(
      tool: ResolvedTool,
      symlink: ResolvedSymlink,
      message: String
  ): ToolInstallError =
    ToolInstallError.SymlinkFailed(tool.name, symlink.path, symlink.target, message)
