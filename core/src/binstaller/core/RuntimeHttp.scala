package binstaller.core

import binstaller.config.Diagnostics

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.InputStream
import scala.annotation.tailrec
import scala.concurrent.duration.DurationLong
import scala.util.Try
import java.time.Duration
import scala.util.Using
import ox.timeoutEither

private[core] object RuntimeHttpClient:
  val requestTimeout: Duration = Duration.ofSeconds(30)

  def create(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(requestTimeout)
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

  private val redirectStatuses = Set(301, 302, 303, 307, 308)
  private val maxRedirects     = 10

  // Every redirect failure has to release the connection before returning the error. Repeating
  // `response.body().close()` at each exit is how one of them eventually gets forgotten and leaks
  // a connection from the pool, so failing and closing is a single operation here.
  private def failClosing(
      response: HttpResponse[InputStream],
      message: String
  ): Either[String, Nothing] =
    RuntimeHttpBody.closeAfterFailure(response.body())
    Left(message)

  def getInputStream(
      client: HttpClient,
      initialUrl: String,
      // Injectable so tests can drive the redirect/status logic against a stubbed transport without
      // live DNS. Production uses the fail-closed resolved check as defense-in-depth.
      hostGuard: String => Either[String, Unit] = NetworkTargetGuard.validateResolved(_)
  ): Either[String, RuntimeHttpResponse] = RuntimeUrl.httpsUri(initialUrl).flatMap: initialUri =>
    @tailrec
    def follow(
        current: URI,
        redirects: Vector[UrlRedirectHop],
        remaining: Int
    ): Either[String, RuntimeHttpResponse] = hostGuard(current.getHost) match
      case Left(message) => Left(message)
      case Right(())     =>
        val request  = HttpRequest.newBuilder(current).timeout(requestTimeout).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if !redirectStatuses(response.statusCode()) then
          // The caller streams this body, so it must stay open.
          val provenance =
            if redirects.nonEmpty then UrlProvenance(initialUrl, current.toString, redirects)
            else UrlProvenance.fromResponse(initialUrl, response)
          Right(RuntimeHttpResponse(response, provenance))
        else if remaining == 0 then
          failClosing(response, s"HTTP redirect limit exceeded ($maxRedirects)")
        else
          val location = response.headers().firstValue("Location")
          if location.isEmpty || location.get().trim.isEmpty then
            failClosing(response, s"HTTP ${response.statusCode()} redirect is missing Location")
          else
            // A malformed Location makes URI.resolve throw IllegalArgumentException; treat it as a
            // failed redirect rather than letting it escape the download boundary uncaught.
            val next = Try(current.resolve(location.get()).toString).toEither
              .left.map(error => s"invalid redirect Location: ${Diagnostics.describe(error)}")
              .flatMap(RuntimeUrl.httpsUri(_).left.map(message =>
                s"unsafe redirect target: $message"
              ))
            next match
              case Left(message) => failClosing(response, message)
              case Right(uri)    =>
                val hop = UrlRedirectHop(current.toString, uri.toString, response.statusCode())
                response.body().close()
                follow(uri, redirects :+ hop, remaining - 1)

    follow(initialUri, Vector.empty, maxRedirects)

private[core] final case class RuntimeHttpResponse(
    response: HttpResponse[InputStream],
    provenance: UrlProvenance
)

private[core] object RuntimeHttpBody:

  def closeAfterFailure(body: InputStream): Unit =
    val _ = Try(body.close())

  def readWithDeadline[E, A](
      body: InputStream,
      timeout: Duration,
      timeoutFailure: E
  )(read: InputStream => Either[E, A]): Either[E, A] = Using.resource(body): input =>
    timeoutEither(timeout.toNanos.nanos, timeoutFailure)(read(input))

private[core] object RuntimeUrl:

  def httpsUri(url: String): Either[String, URI] = HttpsUrl.fromString(url).map(_.uri)
