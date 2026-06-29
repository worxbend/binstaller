package binstaller.app

import binstaller.cli.CliModule

/** JVM/native-image entrypoint that delegates all command behavior to the CLI module. */
object Main:

  /** Run binstaller and exit the process with the CLI result code. */
  def main(args: Array[String]): Unit =
    val exitCode = CliModule.run(args.toVector)
    if exitCode != 0 then sys.exit(exitCode)
