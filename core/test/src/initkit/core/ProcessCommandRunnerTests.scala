package initkit.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import scala.collection.immutable.VectorMap
import scala.concurrent.duration.*

import ox.*
import utest.*

object ProcessCommandRunnerTests extends TestSuite:
  val tests: Tests = Tests:
    test("runs argv commands without shell interpretation by default"):
      val dir = tempDir()
      val touched = dir.resolve("owned")
      val result = liveRunner.run(
        CommandSpec.direct(
          Vector(CommandArgument("printf"), CommandArgument("literal > owned")),
          cwd = Some(dir)
        )
      )

      assert(result.succeeded)
      assert(result.stdout == "literal > owned")
      assert(!Files.exists(touched))

    test("uses shell execution only for explicit shell specs"):
      val dir = tempDir()
      val touched = dir.resolve("owned")
      val result = liveRunner.run(
        CommandSpec.shell(
          CommandArgument("printf shell > owned"),
          cwd = Some(dir)
        )
      )

      assert(result.succeeded)
      assert(Files.readString(touched) == "shell")

    test("captures exit code stdout stderr and environment"):
      val result = liveRunner.run(
        CommandSpec.shell(
          CommandArgument("printf \"$INITKIT_VALUE\"; printf err >&2; exit 7"),
          env = VectorMap("INITKIT_VALUE" -> CommandEnvironmentValue("out"))
        )
      )

      assert(result.exitCode == Some(7))
      assert(result.stdout == "out")
      assert(result.stderr == "err")

    test("dry-run bypasses process start"):
      val result = dryRunRunner.run(
        CommandSpec.direct(Vector(CommandArgument("definitely-not-an-initkit-command")))
      )

      assert(result.succeeded)
      assert(result.stdout == "")
      assert(result.stderr == "")

    test("feeds configured stdin file to direct argv commands"):
      val input = tempDir().resolve("input.txt")
      Files.writeString(input, "from-file", StandardOpenOption.CREATE_NEW)

      val result = liveRunner.run(
        CommandSpec.direct(Vector(CommandArgument("cat")), stdinFile = Some(input))
      )

      assert(result.succeeded)
      assert(result.stdout == "from-file")

    test("large stdout and stderr streams do not deadlock"):
      val script = tempScript(
        "large-streams",
        """#!/bin/sh
          |i=0
          |while [ "$i" -lt 12000 ]; do
          |  printf 'stdout-line-%05d\n' "$i"
          |  printf 'stderr-line-%05d\n' "$i" >&2
          |  i=$((i + 1))
          |done
          |""".stripMargin
      )

      val result = liveRunner.run(CommandSpec.direct(Vector(CommandArgument(script.toString))))

      assert(result.succeeded)
      assert(result.stdout.contains("stdout-line-11999"))
      assert(result.stderr.contains("stderr-line-11999"))

    test("timeout terminates the child process"):
      val dir = tempDir()
      val marker = dir.resolve("started")
      val pidFile = dir.resolve("pid")
      val script = tempScript(
        "timeout",
        s"""#!/bin/sh
           |printf "%s" "$$$$" > '${pidFile.toString}'
           |printf started > '${marker.toString}'
           |sleep 20
           |""".stripMargin
      )

      val result = liveRunner.run(
        CommandSpec.direct(Vector(CommandArgument(script.toString)), timeout = Some(200.millis))
      )

      assert(result.termination == CommandTermination.TimedOut(200.millis))
      assert(Files.readString(marker) == "started")
      assert(!processAlive(Files.readString(pidFile).trim.toLong))

    test("cancellation terminates the child process"):
      val pidFile = tempDir().resolve("pid")
      val script = tempScript(
        "cancel",
        s"""#!/bin/sh
           |printf "%s" "$$$$" > '${pidFile.toString}'
           |sleep 20
           |""".stripMargin
      )

      val result = supervised:
        val forked = forkCancellable:
          liveRunner.run(CommandSpec.direct(Vector(CommandArgument(script.toString))))
        waitForFile(pidFile)
        forked.cancel()

      assert(result.exists(_.termination.isInstanceOf[CommandTermination.Cancelled]))
      assert(!processAlive(Files.readString(pidFile).trim.toLong))

    test("runner calls fake sudo strategy before execution"):
      val original = CommandSpec.direct(Vector(CommandArgument("printf"), CommandArgument("ok")), sudo = SudoMode.Required)
      val prepared = CommandSpec.direct(Vector(CommandArgument("printf"), CommandArgument("ok")), sudo = SudoMode.Disabled)
      val sudo = FakeSudoStrategy(Vector(FakeSudoResponse(original, Right(prepared))))
      val runner = new ProcessCommandRunner(sudo)

      val result = runner.run(original)

      assert(result.succeeded)
      assert(result.stdout == "ok")
      assert(sudo.calls == Vector(original))

    test("interactive sudo preflight is covered through a fake"):
      val preflight = FakeSudoPreflight(
        Vector(FakeSudoPreflightResponse(SudoPreflightRequest(SudoPreflightMode.Interactive), Right(())))
      )
      val strategy = new PreflightSudoStrategy(preflight, SudoPreflightMode.Interactive)
      val spec = CommandSpec.direct(Vector(CommandArgument("apt-get"), CommandArgument("update")), sudo = SudoMode.Required)

      val prepared = strategy.prepare(spec)

      assert(preflight.calls == Vector(SudoPreflightRequest(SudoPreflightMode.Interactive)))
      assert(
        prepared == Right(
          CommandSpec.direct(
            Vector(CommandArgument("sudo"), CommandArgument("apt-get"), CommandArgument("update")),
            sudo = SudoMode.Disabled
          )
        )
      )

    test("noninteractive askpass sudo preflight is covered through a fake"):
      val preflight = FakeSudoPreflight(
        Vector(FakeSudoPreflightResponse(SudoPreflightRequest(SudoPreflightMode.AskPass), Right(())))
      )
      val strategy = new PreflightSudoStrategy(preflight, SudoPreflightMode.AskPass)
      val spec = CommandSpec.shell(CommandArgument("id -u"), sudo = SudoMode.Required)

      val prepared = strategy.prepare(spec)

      assert(preflight.calls == Vector(SudoPreflightRequest(SudoPreflightMode.AskPass)))
      assert(prepared.exists(_.invocation == CommandInvocation.Shell(CommandArgument("id -u"), Vector("sudo", "-A", "/bin/sh", "-c"))))

    test("noninteractive sudo without askpass fails before prompting"):
      val strategy = PreflightSudoStrategy.fromEnvironment(
        SudoInteraction.NonInteractive,
        environment = VectorMap.empty,
        preflight = FakeSudoPreflight(Vector.empty)
      )

      assert(strategy.left.toOption.exists(_.message.contains("SUDO_ASKPASS")))

  private def liveRunner: ProcessCommandRunner =
    new ProcessCommandRunner(SudoStrategy.Passthrough)

  private def dryRunRunner: ProcessCommandRunner =
    new ProcessCommandRunner(SudoStrategy.Passthrough, mode = CommandRunMode.DryRun)

  private def tempDir(): Path =
    Files.createTempDirectory("initkit-process-runner-test-")

  private def tempScript(name: String, content: String): Path =
    val path = tempDir().resolve(name)
    Files.writeString(path, content, StandardOpenOption.CREATE_NEW)
    path.toFile.setExecutable(true)
    path

  private def waitForFile(path: Path): Unit =
    var attempts = 0
    while attempts < 100 && !Files.exists(path) do
      sleep(20.millis)
      attempts += 1
    if !Files.exists(path) then fail(s"timed out waiting for $path")

  private def processAlive(pid: Long): Boolean =
    ProcessHandle.of(pid).isPresent && ProcessHandle.of(pid).get().isAlive

  private def fail(message: String): Nothing =
    throw new java.lang.AssertionError(message)
