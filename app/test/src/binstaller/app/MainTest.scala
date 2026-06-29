package binstaller.app

import utest.*

object MainTest extends TestSuite:

  val tests: Tests = Tests:
    test("main object is loadable"):
      val mainClass = Class.forName("binstaller.app.Main$")
      assert(mainClass.getName == "binstaller.app.Main$")
