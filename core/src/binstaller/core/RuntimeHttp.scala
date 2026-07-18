package binstaller.core

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.InputStream
import scala.util.Try
import scala.util.boundary
import scala.util.boundary.break
import java.time.Duration

private[core] object RuntimeHttpClient:
  val requestTimeout: Duration = Duration.ofSeconds(30)

  def create(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(requestTimeout)
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

  private val redirectStatuses = Set(301, 302, 303, 307, 308)
  private val maxRedirects     = 10

  def getInputStream(
      client: HttpClient,
      initialUrl: String,
      // Injectable so tests can drive the redirect/status logic against a stubbed transport without
      // live DNS. Production uses the fail-closed resolved check as defense-in-depth.
      hostGuard: String => Either[String, Unit] = NetworkTargetGuard.validateResolved
  ): Either[String, RuntimeHttpResponse] = RuntimeUrl.httpsUri(initialUrl).flatMap: initialUri =>
    boundary:
      var current   = initialUri
      var redirects = Vector.empty[UrlRedirectHop]
      var remaining = maxRedirects
      while true do
        hostGuard(current.getHost) match
          case Left(message) => break(Left(message))
          case Right(())     => ()
        val request  = HttpRequest.newBuilder(current).timeout(requestTimeout).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if redirectStatuses(response.statusCode()) then
          if remaining == 0 then
            response.body().close()
            break(Left(s"HTTP redirect limit exceeded ($maxRedirects)"))
          val location = response.headers().firstValue("Location")
          if location.isEmpty || location.get().trim.isEmpty then
            response.body().close()
            break(Left(s"HTTP ${response.statusCode()} redirect is missing Location"))
          // A malformed Location makes URI.resolve throw IllegalArgumentException; treat it as a
          // failed redirect rather than letting it escape the download boundary uncaught.
          Try(current.resolve(location.get()).toString).toEither match
            case Left(error) =>
              response.body().close()
              break(Left(s"invalid redirect Location: ${error.getMessage}"))
            case Right(next) => RuntimeUrl.httpsUri(next) match
                case Left(message) =>
                  response.body().close()
                  break(Left(s"unsafe redirect target: $message"))
                case Right(uri) =>
                  redirects :+= UrlRedirectHop(current.toString, uri.toString, response.statusCode())
                  response.body().close()
                  current = uri
                  remaining -= 1
        else
          val provenance =
            if redirects.nonEmpty then UrlProvenance(initialUrl, current.toString, redirects)
            else UrlProvenance.fromResponse(initialUrl, response)
          break(Right(RuntimeHttpResponse(response, provenance)))
      Left("unreachable HTTP redirect state")

private[core] final case class RuntimeHttpResponse(
    response: HttpResponse[InputStream],
    provenance: UrlProvenance
)

private[core] object RuntimeUrl:

  def httpsUri(url: String): Either[String, URI] = HttpsUrl.fromString(url).map(_.uri)
