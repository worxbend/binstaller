package initkit.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.Duration

import initkit.config.*
import utest.*

object NerdFontsExecutorTests extends TestSuite:

  val tests: Tests = Tests:
    test("renders generated config with selected font families and destination"):
      val operation = nerdFontsOperation("install-nerd-fonts")
      val yaml = operation.spec.config.content.map(NerdFontsExecutor.renderConfig).getOrElse(fail(
        "config content missing"
      ))

      assert(yaml.contains("destination: '${nerdFontDir}'"))
      assert(yaml.contains("families:"))
      assert(yaml.contains("- 'JetBrainsMono'"))
      assert(yaml.contains("- 'Hack'"))
      assert(yaml.contains("- 'FiraCode'"))
      assert(yaml.contains("- 'Meslo'"))

    test("command generation includes preview before apply"):
      val operation = nerdFontsOperation("install-nerd-fonts")
      val commands  = NerdFontsExecutor.commandSpecs(operation)

      assert(argvs(commands) == Vector(
        Vector("${binDir}/nerdfont-install", "-config", "${nerdFontConfig}", "-dry-run"),
        Vector("${binDir}/nerdfont-install", "-config", "${nerdFontConfig}")
      ))
      assert(commands.forall(_.sudo == SudoMode.Disabled))

    test("dry-run shows config creation and preview/apply commands without mutating"):
      val operation = nerdFontsOperation("install-nerd-fonts")
      val executor  = FakeCommandExecutor(Vector.empty)
      val files     = new RecordingNerdFontsFiles
      val installer = new PackageManagerInstallers(
        executor,
        nerdFontsFiles = files
      )

      val outcome = installer.install(PlanOperation.NerdFonts(operation), dryRunPolicy)
      val dryRun  = outcome match
        case PlanOperationOutcome.DryRun(data) => data
        case other                             => fail(s"expected dry-run outcome, got $other")

      assert(executor.calls.isEmpty)
      assert(files.writes.isEmpty)
      assert(dryRun.actions == Vector(
        DryRunAction.FileWrite("${nerdFontConfig}", None, "generate Nerd Fonts config"),
        DryRunAction.Command(
          argv = Vector("${binDir}/nerdfont-install", "-config", "${nerdFontConfig}", "-dry-run"),
          shell = None,
          sudo = false,
          workingDirectory = None
        ),
        DryRunAction.Command(
          argv = Vector("${binDir}/nerdfont-install", "-config", "${nerdFontConfig}"),
          shell = None,
          sudo = false,
          workingDirectory = None
        )
      ))

    test("apply creates config parent, writes YAML, and executes preview before apply"):
      withTempDir: tempDir =>
        val configPath = tempDir.resolve("config").resolve("nerd-fonts").resolve("config.yaml")
        val operation  = tempOperation(configPath)
        val commands   = NerdFontsExecutor.commandSpecs(operation)
        val executor   = FakeCommandExecutor(
          commands.map(command =>
            FakeCommandResponse(command, CommandResultData.exited(0, duration = Duration.Zero))
          )
        )
        val installer = new PackageManagerInstallers(executor)

        val outcome = installer.install(PlanOperation.NerdFonts(operation), applyPolicy)

        assert(outcome == PlanOperationOutcome.Completed(
          Vector("wrote Nerd Fonts config; ran 2 Nerd Fonts commands")
        ))
        assert(Files.isDirectory(configPath.getParent))
        val yaml = Files.readString(configPath, StandardCharsets.UTF_8)
        assert(yaml.contains("destination: '/tmp/initkit-fonts'"))
        assert(yaml.contains("- 'JetBrainsMono'"))
        assert(yaml.contains("- 'Hack'"))
        assert(executor.calls == commands)

    test("apply stops before install when preview command fails"):
      val operation = nerdFontsOperation("install-nerd-fonts")
      val commands  = NerdFontsExecutor.commandSpecs(operation)
      val executor  = FakeCommandExecutor(Vector(
        FakeCommandResponse(commands.head, CommandResultData.exited(2, duration = Duration.Zero))
      ))
      val files     = new RecordingNerdFontsFiles
      val installer = new PackageManagerInstallers(
        executor,
        nerdFontsFiles = files
      )

      val outcome = installer.install(PlanOperation.NerdFonts(operation), applyPolicy)
      val failure = outcome match
        case PlanOperationOutcome.Failed(value) => value
        case other                              => fail(s"expected failure, got $other")

      assert(executor.calls == Vector(commands.head))
      assert(failure.exitCode == Some(2))
      assert(failure.message.contains("Nerd Fonts command failed"))

  private val dryRunPolicy: ExecutionPolicy = ExecutionPolicy(
    mode = ExecutionRunMode.DryRun,
    continueOnError = false,
    requireSudo = true,
    reboot = RebootExecutionPolicy(allowed = false, prompt = true)
  )

  private val applyPolicy: ExecutionPolicy = dryRunPolicy.copy(mode = ExecutionRunMode.Apply)

  private lazy val manifest: Manifest = ManifestLoader.loadValidated(exampleConfigPath) match
    case Right(value) => value
    case Left(error)  => fail(error.message)

  private def nerdFontsOperation(name: String): InstallerPlanOperation[InstallerSpec.NerdFonts] =
    val (entry, index) = manifest.spec.plan.zipWithIndex
      .find((entry, _) => entry.name.contains(name))
      .getOrElse(fail(s"plan entry '$name' not found"))

    PlanOperation.decode(index, entry) match
      case Right(PlanOperation.NerdFonts(operation)) => operation
      case Right(other) => fail(s"plan entry '$name' is not a nerd-fonts operation: $other")
      case Left(errors) => fail(errors.map(_.message).mkString("; "))

  private def tempOperation(configPath: Path): InstallerPlanOperation[InstallerSpec.NerdFonts] =
    nerdFontsOperation("install-nerd-fonts").copy(
      spec = InstallerSpec.NerdFonts(
        tool = ToolInvocation("/tmp/nerdfont-install", Vector("-config", configPath.toString)),
        config = GeneratedConfig(
          path = configPath.toString,
          create = Some(true),
          content = Some(
            RawYaml.MappingValue(
              scala.collection.immutable.VectorMap(
                "release"            -> RawYaml.StringValue("latest"),
                "destination"        -> RawYaml.StringValue("/tmp/initkit-fonts"),
                "refresh_font_cache" -> RawYaml.BooleanValue(true),
                "families"           -> RawYaml.SequenceValue(Vector(
                  RawYaml.StringValue("JetBrainsMono"),
                  RawYaml.StringValue("Hack")
                ))
              )
            )
          )
        ),
        preview = Some(PreviewInvocation(enabled = Some(true), args = Vector("-dry-run")))
      )
    )

  private def argvs(commands: Vector[CommandSpec]): Vector[Vector[String]] = commands.map:
    command =>
      command.invocation match
        case CommandInvocation.Direct(argv) => argv.map(_.value)
        case CommandInvocation.Shell(_, _)  => fail("expected direct command")

  private def withTempDir[A](test: Path => A): A =
    val tempDir = Files.createTempDirectory("initkit-nerd-fonts-test-")
    try test(tempDir)
    finally deleteRecursively(tempDir)

  private def deleteRecursively(path: Path): Unit = if Files.exists(path) then
    val stream = Files.walk(path)
    try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists)
    finally stream.close()

  private def exampleConfigPath: Path = Iterator
    .iterate(Path.of("").toAbsolutePath.normalize())(_.getParent)
    .takeWhile(_ != null)
    .map(_.resolve("config.example.yaml"))
    .find(Files.isRegularFile(_))
    .getOrElse(throw new java.lang.AssertionError("config.example.yaml fixture not found"))

  private def fail(message: String): Nothing = throw new java.lang.AssertionError(message)

private final class RecordingNerdFontsFiles extends NerdFontsFiles:
  private val writesRef = AtomicReference(Vector.empty[(Path, String)])

  def writes: Vector[(Path, String)] = writesRef.get()

  override def writeConfig(path: Path, content: String): Either[NerdFontsFileError, Unit] =
    writesRef.set(writesRef.get() :+ (path -> content))
    Right(())
