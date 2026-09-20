package binstaller.core

import binstaller.config.ConfigModule
import binstaller.config.ArchiveExtract
import binstaller.config.ArchiveSpec
import binstaller.config.ArchiveType
import binstaller.config.ExtractMapping
import binstaller.config.ValidationError
import binstaller.config.SymlinkPrivilege
import utest.*

import java.nio.file.Files
import java.nio.file.Path

private[core] trait CoreTestSupport
    extends TestSuite
    with CoreTestPrimitives
    with ArchiveByteFixtures
    with ManifestYamlFixtures
    with ServiceFactories
    with InstallResultAssertions:

  /** Create a temp directory named `binstaller-<name>-…`, deleted when the suite finishes. */
  protected def tempDirectory(name: String): Path = TestTempDirectories.create(name)

  override def utestAfterAll(): Unit = TestTempDirectories.deleteAll()

  protected def resolve(
      yaml: String,
      httpTextClient: HttpTextClient = FakeHttpTextClient("")
  ): ResolvedPlan =
    val profile = ConfigModule.loadString(yaml) match
      case Right(value) => value
      case Left(error)  => abort(s"expected valid config, got $error")

    PlanResolver.resolve(profile, testResolutionOptions, httpTextClient) match
      case Right(plan)                                     => plan
      case Left(ResolvePlanError.ValidationFailed(errors)) =>
        abort(s"expected resolved plan, got ${errors.mkString(", ")}")

  protected def resolveErrors(yaml: String): Vector[ValidationError] =
    val profile = ConfigModule.loadString(yaml) match
      case Right(value) => value
      case Left(error)  => abort(s"expected config decode success, got $error")

    PlanResolver.resolve(profile, testResolutionOptions, FakeHttpTextClient("")) match
      case Left(ResolvePlanError.ValidationFailed(errors)) => errors
      case Right(plan) => abort(s"expected resolution errors, got $plan")

  protected def resolveExampleConfig(httpTextClient: HttpTextClient): ResolvedPlan =
    val profile = ConfigModule.load(exampleConfigPath) match
      case Right(value) => value
      case Left(error)  => abort(s"expected valid example config, got $error")

    PlanResolver.resolve(profile, testResolutionOptions, httpTextClient) match
      case Right(plan)                                     => plan
      case Left(ResolvePlanError.ValidationFailed(errors)) =>
        abort(s"expected resolved example config, got ${errors.mkString(", ")}")

  protected def onlyTool(plan: ResolvedPlan): ResolvedTool = plan.tools match
    case Vector(tool) => tool
    case other        => abort(s"expected one tool, got ${other.size}")

  protected def errorAt(path: String)(error: ValidationError): Boolean = error.path == path

  protected def versionSummaryRowExists(
      lines: Vector[String],
      packageName: String,
      version: String,
      newerVersion: String
  ): Boolean = lines.exists(line =>
    line.startsWith(packageName) &&
      line.contains(version) &&
      line.endsWith(newerVersion)
  )

  protected def eventIndex(
      events: Vector[InstallerEvent],
      matches: PartialFunction[InstallerEvent, Boolean]
  ): Int =
    val index = events.indexWhere(event => matches.applyOrElse(event, (_: InstallerEvent) => false))
    if index >= 0 then index
    else abort(s"event not found in ${events.mkString(", ")}")

  protected def exampleConfigPath: Path = repoRootCandidates
    .map(_.resolve("config.example.yaml"))
    .find(Files.exists(_))
    .getOrElse(abort("could not locate config.example.yaml"))

  protected def repoRootCandidates: Iterator[Path] =
    sys.props.get("binstaller.repoRoot").iterator.map(Path.of(_).toAbsolutePath) ++
      upwardPaths(Path.of("").toAbsolutePath)

  protected def upwardPaths(start: Path): Iterator[Path] =
    Iterator.iterate(start)(_.getParent).takeWhile(_ != null)

  protected def directTool(
      installDir: Path,
      checksum: Option[ResolvedChecksum] = None,
      executables: Vector[ResolvedExecutable] = Vector(ResolvedExecutable("bin/alpha", None)),
      symlinks: Vector[ResolvedSymlink] = Vector.empty
  ): ResolvedTool = ResolvedTool(
    name = toolName("alpha"),
    description = None,
    version = ResolvedVersion.Concrete("1.0.0"),
    installDir = installDir.toString,
    createDirectories = Vector("bin"),
    download = ResolvedDownload(
      url = "https://example.invalid/alpha",
      filename = "alpha",
      checksum = checksum,
      archive = None
    ),
    executables = executables,
    symlinks = symlinks
  )

  protected def sudoSymlinkTool(installDir: Path): ResolvedTool = directTool(
    installDir,
    symlinks = Vector(
      ResolvedSymlink("/usr/local/bin/alpha", "bin/alpha", SymlinkPrivilege.Sudo)
    )
  )

  /**
   * Install one archive-backed tool whose artifact is exactly `bytes`. Collapses the download
   * client, the file system and the resolved tool into one call; the defaults describe the archive
   * these tests keep reaching for — a tar.gz whose "pkg/alpha" member lands at "bin/alpha" — so a
   * test states only what it varies. The command executor stays a parameter so a test that asserts
   * extraction never shelled out still owns the executor it inspects.
   */
  protected def installArchive(
      installDir: Path,
      bytes: Array[Byte],
      archiveType: ArchiveType = ArchiveType.TarGz,
      files: Vector[(String, String)] = Vector("pkg/alpha" -> "bin/alpha"),
      directories: Vector[(String, String)] = Vector.empty,
      executable: String = "bin/alpha",
      checksum: Option[ResolvedChecksum] = None,
      commandExecutor: CommandExecutor = CommandExecutor.process
  ): Either[ToolInstallError, TerminalToolResult.Completed] = DirectBinaryInstaller(
    FakeBinaryDownloadClient.success(bytes),
    InstallFileSystem.nio,
    commandExecutor
  ).installTool(archiveTool(installDir, archiveType, files, directories, executable, checksum))

  protected def archiveTool(
      installDir: Path,
      archiveType: ArchiveType,
      files: Vector[(String, String)] = Vector.empty,
      directories: Vector[(String, String)] = Vector.empty,
      executable: String = "bin/alpha",
      checksum: Option[ResolvedChecksum] = None
  ): ResolvedTool = ResolvedTool(
    name = toolName("alpha"),
    description = None,
    version = ResolvedVersion.Concrete("1.0.0"),
    installDir = installDir.toString,
    createDirectories = Vector.empty,
    download = ResolvedDownload(
      url = "https://example.invalid/alpha-archive",
      filename = "alpha-archive",
      checksum = checksum,
      archive = Some(
        ResolvedArchive(
          ArchiveSpec(
            archiveType,
            ArchiveExtract(
              files.map((from, to) => ExtractMapping(from, to)),
              directories.map((from, to) => ExtractMapping(from, to))
            )
          ),
          files.map((from, to) => ResolvedExtractMapping(from, to)),
          directories.map((from, to) => ResolvedExtractMapping(from, to))
        )
      )
    ),
    executables = Vector(ResolvedExecutable(executable, None)),
    symlinks = Vector.empty
  )

  protected val testResolutionOptions: ResolutionOptions = ResolutionOptions(
    Map("HOME" -> "/home/test"),
    SensitiveValueRedactions.empty,
    HostPlatform("linux", "amd64")
  )

  protected val exampleToolNames: Vector[String] = Vector(
    "yazi",
    "zig",
    "minikube",
    "xplr",
    "kind",
    "zellij",
    "helm",
    "kubectl",
    "kustomize",
    "neovide",
    "neovim",
    "lazygit",
    "jujutsu",
    "dotbot",
    "nerd-font-installer"
  )

/**
 * Tracks the temp directories a test run creates so they can be deleted afterwards.
 *
 * Nothing in the test tree used to delete one. A full run left dozens behind, several holding
 * staged installs and written binaries, and they accumulated across runs indefinitely — a
 * developer's `/tmp` reaches five figures of them. Registering creation in one place means a new
 * test gets cleanup by using the helper, rather than by remembering to add a teardown.
 *
 * Test fixtures that create directories outside a suite (staging fakes, for instance) register here
 * too, which is why this is an object rather than trait state.
 */
private[core] object TestTempDirectories:

  private val created = java.util.concurrent.ConcurrentLinkedQueue[Path]()

  def create(name: String): Path =
    val directory = Files.createTempDirectory(s"binstaller-$name-")
    val _         = created.add(directory)
    directory

  def deleteAll(): Unit =
    created.forEach(path => { val _ = SafePaths.deleteRecursively(path) })
    created.clear()
