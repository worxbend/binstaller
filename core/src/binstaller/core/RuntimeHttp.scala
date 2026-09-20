package binstaller.core

import binstaller.config.Diagnostics

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.InputStream
import scala.annotation.tailrec
import scala.concurrent.duration.DurationLong
import java.time.Duration
import scala.util.Failure
import scala.util.Success
import scala.util.Try
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
    follow(client, initialUrl, hostGuard, initialUri, Vector.empty, maxRedirects)

  /**
   * The shared GET pipeline for small-response clients: https check, redirects, 2xx guard, then
   * hand the still-open response to `read`, which owns reading and closing the body and reporting
   * its own failures. Non-2xx bodies are closed here; `error` builds the caller's domain error from
   * a message and the provenance observed so far, if any.
   */
  def withSuccessfulStream[E, A](
      client: HttpClient,
      url: String,
      hostGuard: String => Either[String, Unit],
      error: (String, Option[UrlProvenance]) => E
  )(read: RuntimeHttpResponse => Either[E, A]): Either[E, A] = RuntimeUrl.httpsUri(url) match
    case Left(message) => Left(error(message, None))
    case Right(_)      => Try(getInputStream(client, url, hostGuard)) match
        case Success(Right(result))
            if result.response.statusCode() >= 200 && result.response.statusCode() < 300 =>
          read(result)
        case Success(Right(result)) =>
          RuntimeHttpBody.closeAfterFailure(result.response.body())
          Left(error(s"HTTP ${result.response.statusCode()}", Some(result.provenance)))
        case Success(Left(message)) => Left(error(message, None))
        case Failure(cause)         => Left(error(Diagnostics.describe(cause), None))

  // Lives beside `getInputStream` rather than nested inside it: as a local `def` its own branching
  // aggregated into the enclosing method on top of a nesting surcharge, which read as one method
  // doing far more than it does.
  @tailrec
  private def follow(
      client: HttpClient,
      initialUrl: String,
      hostGuard: String => Either[String, Unit],
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
        Right(RuntimeHttpResponse(
          response,
          provenanceFor(initialUrl, current, redirects, response)
        ))
      else if remaining == 0 then
        failClosing(response, s"HTTP redirect limit exceeded ($maxRedirects)")
      else
        // Kept as a `match` rather than a for-comprehension: the recursive call below has to stay
        // in tail position, and moving it inside a `flatMap` lambda breaks `@tailrec`.
        redirectTarget(current, response) match
          case Left(message) => failClosing(response, message)
          case Right(uri)    =>
            val hop = UrlRedirectHop(current.toString, uri.toString, response.statusCode())
            response.body().close()
            follow(client, initialUrl, hostGuard, uri, redirects :+ hop, remaining - 1)

  // Deliberately leaves `response` open on every path, success and failure alike: the body belongs
  // to the caller, which releases it through `failClosing`. Closing here would double-close it.
  private def redirectTarget(
      current: URI,
      response: HttpResponse[InputStream]
  ): Either[String, URI] =
    val location = response.headers().firstValue("Location")
    if location.isEmpty || location.get().trim.isEmpty then
      Left(s"HTTP ${response.statusCode()} redirect is missing Location")
    else
      // A malformed Location makes URI.resolve throw IllegalArgumentException; treat it as a
      // failed redirect rather than letting it escape the download boundary uncaught.
      Try(current.resolve(location.get()).toString).toEither
        .left.map(error => s"invalid redirect Location: ${Diagnostics.describe(error)}")
        .flatMap(RuntimeUrl.httpsUri(_).left.map(message => s"unsafe redirect target: $message"))

  // The two arms draw on different sources on purpose. A chain this loop followed itself is only
  // recorded in `redirects`; a response that never redirected here may still have been redirected
  // by the transport, and only the response carries that history.
  private def provenanceFor(
      initialUrl: String,
      current: URI,
      redirects: Vector[UrlRedirectHop],
      response: HttpResponse[InputStream]
  ): UrlProvenance =
    if redirects.nonEmpty then UrlProvenance(initialUrl, current.toString, redirects)
    else UrlProvenance.fromResponse(initialUrl, response)

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
