package initkit

import java.util.concurrent.Callable

import picocli.CommandLine
import picocli.CommandLine.{Command, Option}
import upickle.default.write

object Main:
  @Command(
    name = "initkit",
    mixinStandardHelpOptions = true,
    version = Array("initkit 0.1.0"),
    subcommands = Array(classOf[InfoCommand], classOf[TuiCommand])
  )
  final class RootCommand extends Runnable:
    override def run(): Unit =
      CommandLine.usage(this, System.out)

  @Command(name = "info", description = Array("Print a workspace snapshot."))
  final class InfoCommand extends Callable[Int]:
    @Option(
      names = Array("-n", "--name"),
      description = Array("Application name to include in the snapshot")
    )
    private var name: String = "initkit"

    @Option(names = Array("--json"), description = Array("Print the snapshot as pretty JSON"))
    private var json: Boolean = false

    override def call(): Int =
      val snapshot = AppSnapshot.collect(name, os.pwd)

      if json then println(write(snapshot, indent = 2))
      else
        println(s"name:  ${snapshot.name}")
        println(s"cwd:   ${snapshot.cwd}")
        println(s"files: ${snapshot.files}")

      0

  @Command(name = "tui", description = Array("Open the starter terminal UI."))
  final class TuiCommand extends Callable[Int]:
    @Option(names = Array("-n", "--name"), description = Array("Name displayed in the TUI"))
    private var name: String = "initkit"

    @Option(names = Array("-t", "--title"), description = Array("Panel title"))
    private var title: String = "Initkit"

    override def call(): Int =
      TambouiApp.run(name, title)
      0

  def main(args: Array[String]): Unit =
    val exitCode = new CommandLine(new RootCommand()).execute(args*)
    sys.exit(exitCode)
