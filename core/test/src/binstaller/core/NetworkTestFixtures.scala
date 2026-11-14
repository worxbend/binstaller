package binstaller.core

import java.io.InputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

/**
 * Transport fake that answers each request with the canned response whose URI it asks for.
 *
 * Like the production client it never follows a redirect itself, so a test that serves a 3xx drives
 * the real follow loop, one request per hop. A request for a URL without a response fails, which
 * catches a loop that resolves a `Location` to the wrong target.
 */
private[core] final class RoutingHttpClient(responses: HttpResponse[InputStream]*)
    extends HttpClient:

  private val responsesByUrl = responses.map(response => response.uri().toString -> response).toMap

  private var requested: Vector[String] = Vector.empty

  def requestedUrls: Vector[String] = requested

  override def send[A](
      request: HttpRequest,
      responseBodyHandler: HttpResponse.BodyHandler[A]
  ): HttpResponse[A] =
    val url = request.uri().toString
    requested = requested :+ url
    responsesByUrl.get(url) match
      case Some(response) => unsafeCast(response)
      case None           => throw java.io.IOException(s"unexpected request for $url")

  // Tests pair this client with stream-bodied clients only, so the caller's handler type is always
  // the canned `InputStream`. Unchecked, so it lives in one named place.
  private def unsafeCast[A](canned: HttpResponse[InputStream]): HttpResponse[A] =
    canned.asInstanceOf[HttpResponse[A]]

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

  override def followRedirects(): HttpClient.Redirect = HttpClient.Redirect.NEVER

  override def cookieHandler(): Optional[CookieHandler] = Optional.empty()

  override def connectTimeout(): Optional[Duration] = Optional.empty()

  override def proxy(): Optional[ProxySelector] = Optional.empty()

  override def sslContext(): SSLContext = SSLContext.getDefault

  override def sslParameters(): SSLParameters = SSLParameters()

  override def authenticator(): Optional[Authenticator] = Optional.empty()

  override def version(): HttpClient.Version = HttpClient.Version.HTTP_1_1

  override def executor(): Optional[Executor] = Optional.empty()
