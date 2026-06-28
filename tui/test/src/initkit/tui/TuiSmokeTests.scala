package initkit.tui

import java.nio.file.{Files, Path, Paths}
import java.time.{Clock, Duration, Instant, ZoneOffset}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.immutable.VectorMap

import dev.tamboui.buffer.DiffResult
import dev.tamboui.layout.{Position, Size}
import dev.tamboui.terminal.Backend
import dev.tamboui.toolkit.app.ToolkitRunner
import dev.tamboui.tui.TuiConfig
import dev.tamboui.tui.event.KeyEvent
import initkit.core.*
import initkit.host.HostFacts
import ox.supervised
import utest.*

object TuiSmokeTests extends TestSuite:
  private val clock: Clock =
    Clock.fixed(Instant.parse("2026-06-28T12:00:00Z"), ZoneOffset.UTC)

  val tests: Tests = Tests:
    test("loads example config renders initial session and quits through key event"):
      val tmp = Files.createTempDirectory("initkit-tui-smoke-")
      try
        val statePath = tmp.resolve("state.json")
        val launch = requireRight(loadLaunchModel(statePath))
        val backend = RecordingBackend()

        supervised:
          val runner = ToolkitRunner.builder().config(smokeConfig(backend)).build()
          try
            val state = TuiAppState(launch)

            runner.tuiRunner().dispatch(KeyEvent.ofChar('q'))
            runner.run(() => TuiRenderer.render(state, runner))

            assert(!runner.isRunning())
          finally runner.close()

        assert(launch.viewModel.profile.name == "developer-workstation")
        assert(launch.viewModel.rows.nonEmpty)
        assert(backend.drawCount > 0)
        assert(backend.closed)
        assert(!Files.exists(statePath))
      finally deleteRecursively(tmp)

  private def loadLaunchModel(statePath: Path): Either[String, TuiLaunchModel] =
    val hostFacts = HostFacts.fake(
      distribution = Some("ubuntu"),
      commands = Set("apt-get", "flatpak")
    )
    val configPath = exampleConfig

    for
      manifest <- ManifestVariableResolver
        .loadValidatedResolved(configPath, runtimeVariables, hostFacts)
        .left
        .map(_.message)
      state <- ExecutionStateStore
        .loadOrInitialize(statePath, manifest, resetState = false, clock)
        .left
        .map(_.message)
      policy = ExecutionPolicy.fromManifest(manifest.spec.policy, Some(ExecutionRunMode.DryRun))
      sourceSetup = SourceSetupGenerator.generate(manifest.spec.sources, hostFacts, policy)
      stateFile = TuiStateFileInput(
        path = statePath,
        existedBeforeLoad = false,
        resetRequested = false
      )
      viewModel = TuiViewModel.from(
        TuiViewModelRequest(
          manifest = manifest,
          hostFacts = hostFacts,
          state = state,
          stateFile = stateFile,
          selection = TuiSelectionInputs.fromOptions(Vector.empty, Vector.empty),
          dryRun = true
        )
      )
    yield
      TuiLaunchModel(
        viewModel = viewModel,
        context = TuiExecutionContext(
          manifest = manifest,
          hostFacts = hostFacts,
          statePath = statePath,
          stateFile = stateFile,
          state = state,
          sourceSetup = sourceSetup,
          configPath = configPath,
          clock = clock
        )
      )

  private def smokeConfig(backend: Backend): TuiConfig =
    TuiConfig
      .builder()
      .backend(backend)
      .rawMode(false)
      .alternateScreen(false)
      .hideCursor(false)
      .shutdownHook(false)
      .pollTimeout(Duration.ofMillis(5))
      .noTick()
      .build()

  private def exampleConfig: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath.normalize())(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("config.example.yaml"))
      .find(Files.isRegularFile(_))
      .getOrElse(throw java.lang.AssertionError("config.example.yaml fixture not found"))

  private def runtimeVariables: RuntimeVariables =
    RuntimeVariables(
      VectorMap.from(
        Vector(
          "HOME" -> sys.env.getOrElse("HOME", System.getProperty("user.home", "")),
          "USER" -> sys.env.getOrElse("USER", System.getProperty("user.name", ""))
        ).filter(_._2.nonEmpty)
      )
    )

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try
        stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(Files.deleteIfExists)
      finally stream.close()

  private def requireRight[A](value: Either[?, A]): A =
    value.fold(error => throw java.lang.AssertionError(error.toString), identity)

private final class RecordingBackend private (
    currentDrawCount: AtomicInteger,
    currentClosed: AtomicBoolean
) extends Backend:
  def drawCount: Int =
    currentDrawCount.get()

  def closed: Boolean =
    currentClosed.get()

  override def draw(diff: DiffResult): Unit =
    currentDrawCount.incrementAndGet()

  override def flush(): Unit = ()

  override def clear(): Unit = ()

  override def size(): Size =
    Size(120, 40)

  override def showCursor(): Unit = ()

  override def hideCursor(): Unit = ()

  override def getCursorPosition(): Position =
    Position(0, 0)

  override def setCursorPosition(position: Position): Unit = ()

  override def enterAlternateScreen(): Unit = ()

  override def leaveAlternateScreen(): Unit = ()

  override def enableRawMode(): Unit = ()

  override def disableRawMode(): Unit = ()

  override def onResize(handler: Runnable): Unit = ()

  override def read(timeoutMs: Int): Int =
    -2

  override def peek(timeoutMs: Int): Int =
    -2

  override def writeRaw(data: Array[Byte]): Unit = ()

  override def close(): Unit =
    currentClosed.set(true)

private object RecordingBackend:
  def apply(): RecordingBackend =
    new RecordingBackend(AtomicInteger(0), AtomicBoolean(false))
