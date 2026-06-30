package binstaller.cli

import binstaller.core.BinaryInstallerService
import picocli.CommandLine

import java.io.PrintWriter
import java.io.StringWriter

/** Factory used by picocli-codegen to inspect dependency-injected command classes. */
final class NativeImagePicocliFactory extends CommandLine.IFactory:
  private val out: PrintWriter                  = PrintWriter(StringWriter())
  private val root: BinstallerCommand           = BinstallerCommand(out)
  private val service: BinaryInstallerService   = BinaryInstallerService.placeholder
  private val outputStyle: CliOutputStyle       = CliOutputStyle.Plain
  private val fallback: CommandLine.IFactory    = CommandLine.defaultFactory()

  override def create[K](cls: Class[K]): K =
    val instance =
      if cls == classOf[BinstallerCommand] then root
      else if cls == classOf[PlanCommand] then PlanCommand(root, service, out)
      else if cls == classOf[ApplyCommand] then ApplyCommand(root, service, out, outputStyle)
      else if cls == classOf[VersionsCommand] then VersionsCommand(root, service, out, outputStyle)
      else if cls == classOf[LockCommand] then LockCommand(root, service, out)
      else fallback.create(cls)

    instance.asInstanceOf[K]
