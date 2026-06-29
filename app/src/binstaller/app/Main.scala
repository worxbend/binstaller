package binstaller.app

import binstaller.cli.CliModule

object Main:

  def main(args: Array[String]): Unit =
    val exitCode = CliModule.run(args.toVector)
    if exitCode != 0 then sys.exit(exitCode)
