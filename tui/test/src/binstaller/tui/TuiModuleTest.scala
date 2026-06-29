package binstaller.tui

import binstaller.config.ChecksumAlgorithm
import binstaller.core.HttpTextClient
import binstaller.core.HttpTextError
import binstaller.core.InstallerOptions
import binstaller.core.ResetState
import binstaller.core.ResolutionOptions
import binstaller.core.ResolvedPlanSnapshot
import binstaller.core.ToolSelection
import binstaller.core.VerboseOutput
import utest.*

import java.nio.file.Files
import java.nio.file.Path

object TuiModuleTest extends TestSuite:

  val tests: Tests = Tests:
    test("module path includes upstream modules"):
      assert(TuiModule.modulePath == Vector("config", "core", "tui"))

    test("plan tui renders a deterministic planning frame"):
      val fixture = writeFixture()

      val result = TuiModule.start(
        TuiRequest(TuiMode.Plan, fixture.options),
        FakeHttpTextClient(""),
        testSettings()
      )

      val plain = stripAnsi(result.lines.mkString("\n"))
      assert(result.exitCode == 0)
      assert(plain.contains("binstaller 1.2.3 | mode plan"))
      assert(plain.contains("manifest tui-profile (BinaryDistributionProfile)"))
      assert(plain.contains(s"config ${fixture.config}"))
      assert(plain.contains(s"state ${fixture.stateFile}"))
      assert(plain.contains("host linux/amd64"))
      assert(plain.contains("selection 1/2 alpha"))
      assert(plain.contains("Plan"))
      assert(plain.contains("Details: alpha"))
      assert(plain.contains("Logs"))
      assert(plain.contains("q quit"))

    test("apply tui uses apply mode while staying in pre-execution preview"):
      val fixture = writeFixture()

      val result = TuiModule.start(
        TuiRequest(TuiMode.Apply, fixture.options),
        FakeHttpTextClient(""),
        testSettings()
      )

      val plain = stripAnsi(result.lines.mkString("\n"))
      assert(result.exitCode == 0)
      assert(plain.contains("mode apply"))
      assert(plain.contains("dry-run operation preview:"))
      assert(!Files.exists(fixture.appsDir))

    test("layout model carries header metadata and plan rows"):
      val fixture = writeFixture()
      val model   = modelFor(fixture, selectedIndex = 0)

      assert(model.header.appName == "binstaller")
      assert(model.header.appVersion == "1.2.3")
      assert(model.header.mode == "plan")
      assert(model.header.manifestName == "tui-profile")
      assert(model.header.configPath == fixture.config.toString)
      assert(model.header.stateFilePath.contains(fixture.stateFile.toString))
      assert(model.rows.map(_.name) == Vector("alpha", "beta"))
      assert(model.rows.map(_.kind).distinct == Vector("binary-tool"))
      assert(model.rows.head.checksumState == ChecksumAlgorithm.Sha256.value)

    test("row selection highlights the active entry and updates details"):
      val fixture = writeFixture()
      val model   = modelFor(fixture, selectedIndex = 1)

      assert(!model.rows.head.selected)
      assert(model.rows(1).selected)
      assert(model.rows(1).status == PlanningTuiStatus.Active)
      assert(model.detail.exists(_.name == "beta"))
      assert(
        model.detail.exists(_.lines.exists(_.contains("https://example.invalid/releases/beta")))
      )

    test("state override is shown instead of manifest state file"):
      val fixture       = writeFixture()
      val overrideState = fixture.root.resolve("override.state.json")
      val snapshot = snapshotFor(fixture.options.copy(statePath = Some(overrideState.toString)))
      val model    = PlanningTuiModel.fromSnapshot(
        snapshot,
        TuiRequest(TuiMode.Plan, fixture.options),
        testSettings()
      )

      val plain = stripAnsi(PlanningTuiRenderer.render(model).mkString("\n"))
      assert(model.header.stateFilePath.contains(overrideState.toString))
      assert(plain.contains(s"state $overrideState"))

    test("risk markers include missing checksums dynamic versions and sudo symlinks"):
      val fixture = writeRiskFixture()
      val model   = modelFor(fixture, selectedIndex = 0)
      val row     = model.rows.head

      assert(row.riskMarkers.contains("no-checksum"))
      assert(row.riskMarkers.contains("dynamic-version"))
      assert(row.riskMarkers.contains("sudo"))
      assert(row.status == PlanningTuiStatus.Active)
      assert(model.footer.contains("sudo symlinks 1"))
      assert(model.footer.contains("missing checksums 1"))

    test("ANSI-stripped rendering is stable and keeps full details"):
      val fixture = writeFixture(longValues = true)
      val model   = modelFor(fixture, selectedIndex = 0, width = 88)

      val first  = PlanningTuiRenderer.render(model).mkString("\n")
      val second = PlanningTuiRenderer.render(model).mkString("\n")
      val plain  = stripAnsi(first)

      assert(stripAnsi(first) == stripAnsi(second))
      assert(!plain.contains("\u001b["))
      assert(plain.contains("…"))
      assert(plain.contains(fixture.longUrl))
      assert(plain.contains(fixture.longInstallDir.toString))
      PlanningTuiStatus.legendOrder.foreach: status =>
        assert(plain.contains(status.label))
      assert(first.contains("\u001b["))

  private def modelFor(
      fixture: TuiFixture,
      selectedIndex: Int,
      width: Int = 120
  ): PlanningTuiModel = PlanningTuiModel.fromSnapshot(
    snapshotFor(fixture.options),
    TuiRequest(TuiMode.Plan, fixture.options),
    testSettings(selectedIndex = selectedIndex, width = width)
  )

  private def snapshotFor(options: InstallerOptions): ResolvedPlanSnapshot = ResolvedPlanSnapshot
    .resolve(
      options,
      FakeHttpTextClient(""),
      ResolutionOptions(Map("HOME" -> options.configPath))
    )
    .fold(
      error => throw new java.lang.AssertionError(error.toString),
      identity
    )

  private def testSettings(
      selectedIndex: Int = 0,
      width: Int = 120
  ): PlanningTuiSettings = PlanningTuiSettings(
    viewport = TuiViewport(width, 34),
    appVersion = "1.2.3",
    hostSummary = "linux/amd64",
    selectedIndex = selectedIndex,
    filter = None,
    logs = Vector("test log line")
  )

  private def writeFixture(longValues: Boolean = false): TuiFixture =
    val root         = Files.createTempDirectory("binstaller-tui")
    val appsDir      = root.resolve("apps")
    val stateFile    = root.resolve("tui.state.json")
    val config       = root.resolve("profile.yaml")
    val longSuffix   = "very-long-directory-name-for-truncation-checks"
    val alphaInstall =
      if longValues then appsDir.resolve(s"alpha-$longSuffix") else appsDir.resolve("alpha")
    val betaInstall = appsDir.resolve("beta")
    val alphaUrl    =
      if longValues then
        "https://example.invalid/releases/alpha/" +
          "very-long-download-url-that-must-remain-visible-in-details/alpha.tar.gz"
      else "https://example.invalid/releases/alpha.tar.gz"
    Files.writeString(
      config,
      s"""
         |apiVersion: binstaller.io/v1alpha1
         |kind: BinaryDistributionProfile
         |metadata:
         |  name: tui-profile
         |spec:
         |  policy:
         |    appsDir: "$appsDir"
         |    stateFile: "$stateFile"
         |    allowSudoSymlinks: true
         |  vars: {}
         |  versions:
         |    alpha: "1.0.0"
         |    beta: "2.0.0"
         |  plan:
         |    - name: alpha
         |      kind: binary-tool
         |      description: Alpha test tool.
         |      spec:
         |        versionRef: alpha
         |        installDir: "$alphaInstall"
         |        download:
         |          url: "$alphaUrl"
         |          filename: alpha.tar.gz
         |          checksum:
         |            algorithm: sha256
         |            value: "1111111111111111111111111111111111111111111111111111111111111111"
         |          archive:
         |            type: tar.gz
         |            extract:
         |              files:
         |                - from: alpha
         |                  to: bin/alpha
         |        executables:
         |          - path: bin/alpha
         |    - name: beta
         |      kind: binary-tool
         |      description: Beta test tool.
         |      spec:
         |        versionRef: beta
         |        installDir: "$betaInstall"
         |        download:
         |          url: https://example.invalid/releases/beta/beta-linux-amd64
         |          filename: beta
         |          checksum:
         |            algorithm: sha256
         |            value: "2222222222222222222222222222222222222222222222222222222222222222"
         |        executables:
         |          - path: bin/beta
         |        symlinks:
         |          - path: bin/beta
         |            target: bin/beta
         |""".stripMargin
    )
    TuiFixture(
      root,
      config,
      appsDir,
      stateFile,
      alphaUrl,
      alphaInstall,
      installerOptions(config)
    )

  private def writeRiskFixture(): TuiFixture =
    val root      = Files.createTempDirectory("binstaller-tui-risk")
    val appsDir   = root.resolve("apps")
    val stateFile = root.resolve("risk.state.json")
    val config    = root.resolve("profile.yaml")
    val install   = appsDir.resolve("gamma")
    val url       = "https://example.invalid/releases/latest/gamma"
    Files.writeString(
      config,
      s"""
         |apiVersion: binstaller.io/v1alpha1
         |kind: BinaryDistributionProfile
         |metadata:
         |  name: risk-profile
         |spec:
         |  policy:
         |    appsDir: "$appsDir"
         |    stateFile: "$stateFile"
         |    allowSudoSymlinks: true
         |  vars: {}
         |  versions:
         |    gamma:
         |      dynamic:
         |        type: latest-url
         |        note: latest endpoint
         |  plan:
         |    - name: gamma
         |      kind: binary-tool
         |      spec:
         |        versionRef: gamma
         |        installDir: "$install"
         |        download:
         |          url: "$url"
         |          filename: gamma
         |        executables:
         |          - path: bin/gamma
         |        symlinks:
         |          - path: /usr/local/bin/gamma
         |            target: "$install/bin/gamma"
         |            sudo: true
         |""".stripMargin
    )
    TuiFixture(root, config, appsDir, stateFile, url, install, installerOptions(config))

  private def installerOptions(config: Path): InstallerOptions = InstallerOptions(
    configPath = config.toString,
    statePath = None,
    resetState = ResetState.Disabled,
    verboseOutput = VerboseOutput.Disabled,
    selection = ToolSelection.all
  )

  private def stripAnsi(output: String): String = output.replaceAll("\u001b\\[[;\\d]*m", "")

private final case class TuiFixture(
    root: Path,
    config: Path,
    appsDir: Path,
    stateFile: Path,
    longUrl: String,
    longInstallDir: Path,
    options: InstallerOptions
)

private final class FakeHttpTextClient(text: String) extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] = Right(text)
