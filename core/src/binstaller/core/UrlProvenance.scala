package binstaller.core

import upickle.default.*

/** One followed HTTP redirect from a response URL to the next request URL. */
final case class UrlRedirectHop(from: String, to: String, statusCode: Int)

/** Initial and final effective URLs observed at an HTTP boundary. */
final case class UrlProvenance(
    initialUrl: String,
    finalUrl: String,
    redirects: Vector[UrlRedirectHop]
):
  /** Whether the final URL differs from the request URL through a followed redirect. */
  def redirected: Boolean = redirects.nonEmpty || initialUrl != finalUrl

  /**
   * This provenance with the query string and fragment dropped from every redirect-side URL.
   *
   * CDNs sign redirect targets with expiring query parameters, so two fetches of one artifact never
   * agree on them. The initial URL is the one the manifest chose and stays exact.
   */
  def withoutRedirectQueries: UrlProvenance = copy(
    finalUrl = UrlProvenance.withoutQuery(finalUrl),
    redirects = redirects.map: hop =>
      hop.copy(from = UrlProvenance.withoutQuery(hop.from), to = UrlProvenance.withoutQuery(hop.to))
  )

/** URL provenance constructors and rendering helpers. */
object UrlProvenance:
  /** Provenance for a response that did not follow redirects. */
  def direct(url: String): UrlProvenance = UrlProvenance(url, url, Vector.empty)

  /** Render a compact parenthetical suffix only when redirects occurred. */
  def redirectSuffix(
      provenance: Option[UrlProvenance],
      redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
  ): String = provenance.filter(_.redirected) match
    case Some(value) => s" (${RenderSafety.display(redirectSummary(value), redactions)})"
    case None        => ""

  /** Render detailed provenance lines only when redirects occurred. */
  def redirectDetailLines(
      label: String,
      provenance: Option[UrlProvenance],
      redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
  ): Vector[String] = provenance.filter(_.redirected) match
    case Some(value) => RenderSafety.displayLines(
        Vector(
          s"$label initial url: ${value.initialUrl}",
          s"$label final url: ${value.finalUrl}",
          s"$label redirects: ${redirectChain(value)}"
        ),
        redactions
      )
    case None => Vector.empty

  /** Render the redirect chain without applying terminal safety; callers scrub at the boundary. */
  def redirectChainForDisplay(provenance: UrlProvenance): String = redirectChain(provenance)

  given ReadWriter[UrlRedirectHop] = macroRW
  given ReadWriter[UrlProvenance]  = macroRW

  // Plain text cut, not URI parsing: lock files are hand-editable, so the value may not be a URI.
  private def withoutQuery(url: String): String =
    url.takeWhile(character => character != '?' && character != '#')

  private def redirectSummary(provenance: UrlProvenance): String =
    s"final url: ${provenance.finalUrl}; redirects: ${redirectChain(provenance)}"

  private def redirectChain(provenance: UrlProvenance): String =
    if provenance.redirects.isEmpty then s"${provenance.initialUrl} -> ${provenance.finalUrl}"
    else
      provenance.redirects
        .map(hop => s"${hop.statusCode} ${hop.from} -> ${hop.to}")
        .mkString(", ")
