package binstaller.core

import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import scala.jdk.CollectionConverters.*
private[core] final class FakeHttpTextClient(text: String) extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] =
    if url == "https://dl.k8s.io/release/stable.txt" then Right(text)
    else Left(HttpTextError(url, s"unexpected URL $url"))

private[core] final class RoutingHttpTextClient(
    responses: Map[String, Either[HttpTextError, HttpTextResponse]]
) extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] = getTextWithProvenance(url).map(_.text)

  override def getTextWithProvenance(url: String): Either[HttpTextError, HttpTextResponse] =
    responses.getOrElse(url, Left(HttpTextError(url, s"unexpected URL $url")))

private[core] final class LockHttpTextClient(text: String, provenance: UrlProvenance)
    extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] = getTextWithProvenance(url).map(_.text)

  override def getTextWithProvenance(url: String): Either[HttpTextError, HttpTextResponse] =
    if url == provenance.initialUrl then Right(HttpTextResponse(text, provenance))
    else Left(HttpTextError(url, s"unexpected URL $url"))

private[core] final class FakeBinaryDownloadClient(result: Either[BinaryDownloadError, Array[Byte]])
    extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] =
    result.left.map(error => error.copy(url = url))

private object FakeBinaryDownloadClient:

  def success(bytes: Array[Byte]): FakeBinaryDownloadClient = FakeBinaryDownloadClient(Right(bytes))

  def failure(message: String): FakeBinaryDownloadClient =
    FakeBinaryDownloadClient(Left(BinaryDownloadError("", message)))

private[core] final class ProgressingBinaryDownloadClient(bytes: Array[Byte])
    extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] = Right(bytes)

  override def download(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, Array[Byte]] =
    val halfway = bytes.length.toLong / 2L
    val total   = Some(bytes.length.toLong)
    progressObserver.onProgress(BinaryDownloadProgress.Started(url, total))
    progressObserver.onProgress(BinaryDownloadProgress.Advanced(url, halfway, total))
    progressObserver.onProgress(BinaryDownloadProgress.Finished(url, bytes.length.toLong, total))
    Right(bytes)

private[core] final class RedirectingBinaryDownloadClient(provenance: UrlProvenance)
    extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] =
    Right("alpha".getBytes(StandardCharsets.UTF_8))

  override def downloadWithProvenance(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, BinaryDownloadResult] =
    val bytes = "alpha".getBytes(StandardCharsets.UTF_8)
    progressObserver.onProgress(BinaryDownloadProgress.Started(provenance.finalUrl, Some(5L)))
    progressObserver.onProgress(BinaryDownloadProgress.Advanced(provenance.finalUrl, 5L, Some(5L)))
    progressObserver.onProgress(BinaryDownloadProgress.Finished(provenance.finalUrl, 5L, Some(5L)))
    Right(BinaryDownloadResult(bytes, provenance))

private[core] final class RecordingBinaryDownloadProgressObserver extends BinaryDownloadProgressObserver:
  private var recordedUrls: Vector[String] = Vector.empty

  def urls: Vector[String] = recordedUrls

  def onProgress(progress: BinaryDownloadProgress): Unit =
    val url = progress match
      case BinaryDownloadProgress.Started(value, _)     => value
      case BinaryDownloadProgress.Advanced(value, _, _) => value
      case BinaryDownloadProgress.Finished(value, _, _) => value
    recordedUrls = recordedUrls :+ url

private[core] final class RoutingBinaryDownloadClient(
    results: Map[String, Either[String, Array[Byte]]]
) extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] = results
    .getOrElse(url, Left(s"unexpected URL $url"))
    .left
    .map(message => BinaryDownloadError(url, message))

private object RoutingBinaryDownloadClient:

  def success: RoutingBinaryDownloadClient = RoutingBinaryDownloadClient(Map(
    "https://example.invalid/alpha" -> Right("alpha".getBytes(StandardCharsets.UTF_8)),
    "https://example.invalid/beta"  -> Right("beta".getBytes(StandardCharsets.UTF_8))
  ))

private[core] final class ConcurrentTrackingDownloadClient(urls: Vector[String])
    extends BinaryDownloadClient:

  private val expectedStarts = CountDownLatch(urls.size)
  private val active         = AtomicInteger(0)
  private val peak           = AtomicInteger(0)

  private val payloads =
    urls.map(url => url -> fileName(url).getBytes(StandardCharsets.UTF_8)).toMap

  def maxInFlight: Int = peak.get()

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] =
    downloadWithProvenance(url).map(_.bytes)

  override def downloadWithProvenance(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, BinaryDownloadResult] = payloads.get(url) match
    case None        => Left(BinaryDownloadError(url, s"unexpected URL $url"))
    case Some(bytes) =>
      val current = active.incrementAndGet()
      updatePeak(current)
      expectedStarts.countDown()
      val _ = expectedStarts.await(5, TimeUnit.SECONDS)
      try
        progressObserver.onProgress(BinaryDownloadProgress.Started(url, Some(bytes.length.toLong)))
        progressObserver.onProgress(
          BinaryDownloadProgress.Advanced(url, bytes.length.toLong, Some(bytes.length.toLong))
        )
        progressObserver.onProgress(
          BinaryDownloadProgress.Finished(url, bytes.length.toLong, Some(bytes.length.toLong))
        )
        Right(BinaryDownloadResult(bytes, UrlProvenance.direct(url)))
      finally
        val _ = active.decrementAndGet()

  private def updatePeak(current: Int): Unit =
    val _ = peak.updateAndGet(previous => math.max(previous, current))

  private def fileName(url: String): String = url.split('/').toVector.lastOption.getOrElse(url)

private[core] final class ParallelismProbeDownloadClient extends BinaryDownloadClient:

  private val active = AtomicInteger(0)
  private val peak   = AtomicInteger(0)
  private val bothStarted = CountDownLatch(2)

  def maxInFlight: Int = peak.get()

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] =
    val current = active.incrementAndGet()
    val _       = peak.updateAndGet(previous => math.max(previous, current))
    bothStarted.countDown()
    val _ = bothStarted.await(150, TimeUnit.MILLISECONDS)
    try Right(url.split('/').last.getBytes(StandardCharsets.UTF_8))
    finally
      val _ = active.decrementAndGet()

private[core] final class RoutingBinaryMetadataClient(results: Map[String, BinaryMetadata])
    extends BinaryMetadataClient:

  def metadata(url: String): Either[BinaryMetadataError, BinaryMetadata] = results
    .get(url)
    .toRight(BinaryMetadataError(url, s"unexpected URL $url"))

private[core] final class RecordingInstallerEventObserver extends InstallerEventObserver:

  private var recordedEvents: Vector[InstallerEvent] = Vector.empty

  def events: Vector[InstallerEvent] = recordedEvents

  def onEvent(event: InstallerEvent): Unit = recordedEvents = recordedEvents :+ event

private[core] final class RecordingApplyStateStore(delegate: ApplyStateStore) extends ApplyStateStore:

  private var states: Vector[ApplyState] = Vector.empty

  def savedStates: Vector[ApplyState] = states

  def cwd: Path = delegate.cwd

  def load(path: Path): Either[ApplyStateError, Option[ApplyState]] = delegate.load(path)

  def save(path: Path, state: ApplyState): Either[ApplyStateError, Unit] =
    states = states :+ state
    delegate.save(path, state)

private[core] final class StaticHttpClient[T](response: HttpResponse[T]) extends HttpClient:

  override def cookieHandler(): Optional[CookieHandler] = Optional.empty()

  override def connectTimeout(): Optional[Duration] = Optional.empty()

  override def followRedirects(): HttpClient.Redirect = HttpClient.Redirect.NORMAL

  override def proxy(): Optional[ProxySelector] = Optional.empty()

  override def sslContext(): SSLContext = SSLContext.getDefault

  override def sslParameters(): SSLParameters = SSLParameters()

  override def authenticator(): Optional[Authenticator] = Optional.empty()

  override def version(): HttpClient.Version = HttpClient.Version.HTTP_1_1

  override def executor(): Optional[Executor] = Optional.empty()

  override def send[A](
      request: HttpRequest,
      responseBodyHandler: HttpResponse.BodyHandler[A]
  ): HttpResponse[A] = response.asInstanceOf[HttpResponse[A]]

  override def sendAsync[A](
      request: HttpRequest,
      responseBodyHandler: HttpResponse.BodyHandler[A]
  ): CompletableFuture[HttpResponse[A]] =
    CompletableFuture.completedFuture(send(request, responseBodyHandler))

  override def sendAsync[A](
      request: HttpRequest,
      responseBodyHandler: HttpResponse.BodyHandler[A],
      pushPromiseHandler: HttpResponse.PushPromiseHandler[A]
  ): CompletableFuture[HttpResponse[A]] =
    CompletableFuture.completedFuture(send(request, responseBodyHandler))

  override def newWebSocketBuilder(): WebSocket.Builder =
    throw UnsupportedOperationException("websocket not used in tests")

private final case class FakeHttpResponse[T](
    responseUri: String,
    responseStatusCode: Int,
    responseBody: T,
    previous: Option[HttpResponse[T]] = None,
    responseHeaders: Map[String, Vector[String]] = Map.empty
) extends HttpResponse[T]:

  def statusCode(): Int = responseStatusCode

  def request(): HttpRequest = HttpRequest.newBuilder(URI.create(responseUri)).build()

  def previousResponse(): Optional[HttpResponse[T]] = previous match
    case Some(response) => Optional.of(response)
    case None           => Optional.empty()

  def headers(): HttpHeaders = HttpHeaders.of(
    responseHeaders.view.mapValues(_.asJava).toMap.asJava,
    (_, _) => true
  )

  def body(): T = responseBody

  def sslSession(): Optional[SSLSession] = Optional.empty()

  def uri(): URI = URI.create(responseUri)

  def version(): HttpClient.Version = HttpClient.Version.HTTP_1_1

private[core] final class FakeArchiveCommandExecutor(path: String, content: String)
    extends CommandExecutor:

  private var recordedCommands: Vector[CommandSpec] = Vector.empty

  def commands: Vector[CommandSpec] = recordedCommands

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] =
    recordedCommands = recordedCommands :+ spec
    val directoryFlag = if spec.argv.contains("--directory") then "--directory" else "-C"
    val extractDir = spec.argv.dropWhile(_ != directoryFlag).drop(1).headOption.map(Path.of(_))
    extractDir match
      case Some(directory) =>
        val target = directory.resolve(path)
        Files.createDirectories(target.getParent)
        Files.writeString(target, content)
        Right(())
      case None => Left(CommandExecutionError(spec, "missing -C extraction directory", None))

private[core] final class RecordingCommandExecutor(result: Either[String, Unit] = Right(()))
    extends CommandExecutor:

  private var recordedCommands: Vector[CommandSpec] = Vector.empty

  def commands: Vector[CommandSpec] = recordedCommands

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] =
    recordedCommands = recordedCommands :+ spec
    result.left.map(message => CommandExecutionError(spec, message, None))

private[core] final class SequencedCommandExecutor(results: Vector[Either[String, Unit]])
    extends CommandExecutor:

  private var recordedCommands: Vector[CommandSpec] = Vector.empty

  def commands: Vector[CommandSpec] = recordedCommands

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] =
    recordedCommands = recordedCommands :+ spec
    val index  = recordedCommands.size - 1
    val result = results.lift(index).getOrElse(Right(()))
    result.left.map(message => CommandExecutionError(spec, message, None))

private[core] final class PasswordLeakingCommandExecutor(password: String) extends CommandExecutor:

  private var recordedCommands: Vector[CommandSpec] = Vector.empty

  def commands: Vector[CommandSpec] = recordedCommands

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] =
    recordedCommands = recordedCommands :+ spec
    if spec.argv == Vector("sudo", "-n", "true") then
      Left(CommandExecutionError(spec, "sudo password required", Some(1)))
    else
      Left(CommandExecutionError(
        spec,
        s"authentication failed with $password",
        Some(1),
        CommandOutput(s"stdout $password", s"stderr $password")
      ))

private[core] final class RecordingSudoCredentialProvider(
    result: Either[SudoCredentialError, SudoPassword]
) extends SudoCredentialProvider:

  private var recordedRequests: Vector[SudoCredentialRequest] = Vector.empty

  def requests: Vector[SudoCredentialRequest] = recordedRequests

  def requestSudoPassword(
      request: SudoCredentialRequest
  ): Either[SudoCredentialError, SudoPassword] =
    recordedRequests = recordedRequests :+ request
    result

private[core] final class ConcurrentTrackingSudoCredentialProvider extends SudoCredentialProvider:

  private val active   = AtomicInteger(0)
  private val peak     = AtomicInteger(0)
  private val requests = ConcurrentLinkedQueue[String]()

  def maxInFlight: Int = peak.get()

  def toolNames: Vector[String] = requests.iterator().asScala.toVector

  def requestSudoPassword(
      request: SudoCredentialRequest
  ): Either[SudoCredentialError, SudoPassword] =
    val _       = requests.add(request.toolName)
    val current = active.incrementAndGet()
    val _       = peak.updateAndGet(previous => math.max(previous, current))
    try
      Thread.sleep(50)
      Right(SudoPassword.fromString("secret"))
    finally
      val _ = active.decrementAndGet()

private[core] final class PasswordPromptCommandExecutor extends CommandExecutor:

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] =
    if spec.argv == Vector("sudo", "-n", "true") then
      Left(CommandExecutionError(spec, "sudo password required", Some(1)))
    else Right(())

private[core] final class RecordingInstallFileSystem(
    stageFailure: Option[String] = None,
    modeFailure: Option[String] = None,
    stagedFiles: Vector[String] = Vector("bin/alpha")
) extends InstallFileSystem:

  private var modes: Vector[ExecutableModeRequest] = Vector.empty
  private var replacements: Int                    = 0

  def recordedModes: Vector[ExecutableModeRequest] = modes

  def replaceCalls: Int = replacements

  def stageDirectBinary(
      installDir: Path,
      createDirectories: Vector[String],
      executablePath: String,
      bytes: Array[Byte]
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] = stageFailure match
    case Some(message) => Left(InstallFileSystemError.StagingFailed(message))
    case None          => stageSuccess(installDir)

  def stageArchive(
      installDir: Path,
      createDirectories: Vector[String],
      archive: ResolvedArchive,
      bytes: Array[Byte],
      commandExecutor: CommandExecutor
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] = stageFailure match
    case Some(message) => Left(InstallFileSystemError.StagingFailed(message))
    case None          => stageSuccess(installDir)

  def applyExecutableModes(
      stagedInstall: StagedInstall,
      executables: Vector[ExecutableModeRequest]
  ): Either[InstallFileSystemError.ModeApplicationFailed, Unit] =
    modes = executables
    modeFailure match
      case Some(message) =>
        val first = executables.head
        Left(
          InstallFileSystemError.ModeApplicationFailed(
            first.path,
            first.mode.octal,
            message
          )
        )
      case None => Right(())

  def replaceInstall(
      stagedInstall: StagedInstall
  ): Either[InstallFileSystemError.ReplacementFailed, Unit] =
    replacements = replacements + 1
    modes.foreach: mode =>
      val target = stagedInstall.installDir.resolve(mode.path)
      Files.createDirectories(target.getParent)
      Files.writeString(target, "installed")
    Right(())

  def discardStaged(stagedInstall: StagedInstall): Unit = ()

  private def stageSuccess(
      installDir: Path
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] =
    try
      val stagingDir = Files.createTempDirectory("binstaller-recording-stage")
      stagedFiles.foreach: file =>
        val target = stagingDir.resolve(file)
        Files.createDirectories(target.getParent)
        Files.writeString(target, "staged")
      Right(StagedInstall(stagingDir, installDir))
    catch
      case error: Exception => Left(InstallFileSystemError.StagingFailed(error.getMessage))
