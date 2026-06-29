package binstaller.cli

import utest.*

object CliModuleTest extends TestSuite:

  val tests: Tests = Tests:
    test("module path includes upstream modules"):
      assert(CliModule.modulePath == Vector("config", "core", "cli"))

    test("startup text includes supplied arguments"):
      assert(CliModule.renderStartup(Vector("--help")) == "binstaller scaffold --help")
