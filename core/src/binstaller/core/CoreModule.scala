package binstaller.core

import binstaller.config.ConfigModule

object CoreModule:
  def modulePath: Vector[String] = Vector(ConfigModule.moduleName, "core")
