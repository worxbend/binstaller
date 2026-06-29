package binstaller.config

import utest.*

object ConfigModuleTest extends TestSuite:

  val tests: Tests = Tests:
    test("module exposes its name"):
      assert(ConfigModule.moduleName == "config")
