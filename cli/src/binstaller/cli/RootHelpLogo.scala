package binstaller.cli

import picocli.CommandLine
import picocli.CommandLine.Help
import picocli.CommandLine.IHelpSectionRenderer
import picocli.CommandLine.Model.UsageMessageSpec

import java.util.LinkedHashMap

private[cli] object RootHelpLogo:

  def install(commandLine: CommandLine): Unit =
    val sectionMap = LinkedHashMap[String, IHelpSectionRenderer](commandLine.getHelpSectionMap)
    sectionMap.put(UsageMessageSpec.SECTION_KEY_HEADER, render)
    val _ = commandLine.getCommandSpec.usageMessage.sectionMap(sectionMap)

  private val render: IHelpSectionRenderer = new IHelpSectionRenderer:
    override def render(help: Help): String =
      if help.commandSpec.name == "binstaller" then logo else help.header()

  private def logo: String =
    val lines = Vector(
      " _     _           _        _ _           ",
      "| |__ (_)_ __  ___| |_ __ _| | | ___ _ __ ",
      "| '_ \\| | '_ \\/ __| __/ _` | | |/ _ \\ '__|",
      "| |_) | | | | \\__ \\ || (_| | | |  __/ |   ",
      "|_.__/|_|_| |_|___/\\__\\__,_|_|_|\\___|_|   "
    )
    val colors = Vector(fansi.Color.Cyan, fansi.Color.Blue, fansi.Color.Magenta)
    val title  = fansi.Bold.On(fansi.Color.Green("binary installer"))
    lines.zipWithIndex
      .map((line, index) => colors(index % colors.size)(line).toString)
      .appended(s"  ${title.toString}")
      .mkString("", "\n", "\n\n")
