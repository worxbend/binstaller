package binstaller.cli

import binstaller.core.CoreModule

object CliModule:
  def modulePath: Vector[String] = CoreModule.modulePath :+ "cli"

  def renderStartup(args: Vector[String]): String =
    val suffix = if args.isEmpty then "" else s" ${args.mkString(" ")}"
    s"binstaller scaffold${suffix}"

  def run(args: Vector[String]): Int =
    println(renderStartup(args))
    0
