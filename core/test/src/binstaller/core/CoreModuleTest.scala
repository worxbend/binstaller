package binstaller.core

import utest.*

object CoreModuleTest extends TestSuite:

  val tests: Tests = Tests:
    test("module path includes config before core"):
      assert(CoreModule.modulePath == Vector("config", "core"))
