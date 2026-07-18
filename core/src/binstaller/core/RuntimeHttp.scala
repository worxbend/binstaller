package binstaller.core

import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

private[core] object RuntimeHttpClient:
  val requestTimeout: Duration = Duration.ofSeconds(30)

  def create(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(requestTimeout)
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

private[core] object RuntimeUrl:

  def httpsUri(url: String): Either[String, URI] = HttpsUrl.fromString(url).map(_.uri)
