package binstaller.core

import java.net.URI
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** GitHub release metadata used to annotate `versions` output. */
private[core] object GitHubReleaseVersions:

  /** Whether a newer GitHub release exists for a tool, or the status could not be determined. */
  enum LatestReleaseStatus:
    case Newer(tag: String)
    case UpToDate
    case Unknown

  def versionStatusByTool(
      plan: ResolvedPlan,
      httpTextClient: HttpTextClient
  ): Map[String, LatestReleaseStatus] = candidates(plan)
    .view
    .map(candidate => candidate.toolName -> latestStatus(candidate, httpTextClient))
    .toMap

  private def candidates(
      plan: ResolvedPlan
  ): Vector[GitHubReleaseCandidate] = plan.tools.flatMap: tool =>
    for
      current <- concreteVersion(tool.version)
      repo    <- GitHubRepo.fromReleaseDownloadUrl(tool.download.url)
    yield GitHubReleaseCandidate(tool.name, repo, current)

  private def concreteVersion(version: ResolvedVersion): Option[String] = version match
    case ResolvedVersion.Concrete(value, _) if value.nonEmpty => Some(value)
    case ResolvedVersion.Concrete(_, _)                       => None
    case ResolvedVersion.DynamicLatestUrl(_)                  => None

  // A failed latest-release fetch (rate limit, network, malformed JSON) or an unorderable version
  // pair yields Unknown so callers can distinguish "could not check" from a genuine "up to date".
  private def latestStatus(
      candidate: GitHubReleaseCandidate,
      httpTextClient: HttpTextClient
  ): LatestReleaseStatus = latestTag(candidate.repo, httpTextClient) match
    case Left(_)    => LatestReleaseStatus.Unknown
    case Right(tag) => VersionOrdering.compare(tag, candidate.current) match
        case VersionOrder.Greater                   => LatestReleaseStatus.Newer(tag)
        case VersionOrder.Equal | VersionOrder.Less => LatestReleaseStatus.UpToDate
        case VersionOrder.Unknown                   => LatestReleaseStatus.Unknown

  private def latestTag(repo: GitHubRepo, httpTextClient: HttpTextClient): Either[String, String] =
    httpTextClient.getText(repo.latestReleaseApiUrl)
      .left.map(_.message)
      .flatMap(parseLatestTag)

  private def parseLatestTag(json: String): Either[String, String] =
    Try(ujson.read(json)("tag_name").str.trim) match
      case Success(value) if value.nonEmpty => Right(value)
      case Success(_)                       => Left("empty tag_name")
      case Failure(error) => Left(s"invalid GitHub release JSON: ${error.getMessage}")

private[core] final case class GitHubReleaseCandidate(
    toolName: String,
    repo: GitHubRepo,
    current: String
)

private[core] final case class GitHubRepo(owner: String, name: String):
  def latestReleaseApiUrl: String = s"https://api.github.com/repos/$owner/$name/releases/latest"

private[core] object GitHubRepo:

  def fromReleaseDownloadUrl(url: String): Option[GitHubRepo] = Try(URI.create(url)) match
    case Success(uri) if Option(uri.getHost).contains("github.com") =>
      releaseDownloadPathSegments(uri).collect:
        case owner +: repo +: "releases" +: "download" +: _ if owner.nonEmpty && repo.nonEmpty =>
          GitHubRepo(owner, repo)
    case _ => None

  private def releaseDownloadPathSegments(uri: URI): Option[Vector[String]] =
    Option(uri.getPath).map:
      _.split('/').toVector.filter(_.nonEmpty)

private[core] enum VersionOrder:
  case Greater, Equal, Less, Unknown

private[core] object VersionOrdering:

  private val SemanticVersion =
    """^[vV]?(\d+(?:\.\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""".r

  def compare(left: String, right: String): VersionOrder = (parse(left), parse(right)) match
    case (Some((leftNumbers, leftPre)), Some((rightNumbers, rightPre))) =>
      compareNumbers(leftNumbers, rightNumbers) match
        case VersionOrder.Equal => comparePrerelease(leftPre, rightPre)
        case order              => order
    case _ => VersionOrder.Unknown

  private def parse(value: String): Option[(Vector[Int], Option[Vector[String]])] = value match
    case SemanticVersion(numbers, prerelease) => Try:
        numbers.split("\\.").toVector.map(_.toInt) ->
          Option(prerelease).map(_.split("\\.").toVector)
      .toOption
    case _ => None

  private def compareNumbers(left: Vector[Int], right: Vector[Int]): VersionOrder =
    val size   = left.size.max(right.size)
    val padded = left.padTo(size, 0).zip(right.padTo(size, 0))
    padded.collectFirst:
      case (leftValue, rightValue) if leftValue > rightValue => VersionOrder.Greater
      case (leftValue, rightValue) if leftValue < rightValue => VersionOrder.Less
    .getOrElse(VersionOrder.Equal)

  private def comparePrerelease(
      left: Option[Vector[String]],
      right: Option[Vector[String]]
  ): VersionOrder = (left, right) match
    case (None, None)                    => VersionOrder.Equal
    case (None, Some(_))                 => VersionOrder.Greater
    case (Some(_), None)                 => VersionOrder.Less
    case (Some(leftIds), Some(rightIds)) => leftIds.zipAll(rightIds, "", "").collectFirst:
        case ("", _)                                => VersionOrder.Less
        case (_, "")                                => VersionOrder.Greater
        case (leftId, rightId) if leftId != rightId => compareIdentifier(leftId, rightId)
      .getOrElse(VersionOrder.Equal)

  private def compareIdentifier(left: String, right: String): VersionOrder =
    (left.toIntOption, right.toIntOption) match
      case (Some(leftNumber), Some(rightNumber)) => compareInt(leftNumber, rightNumber)
      case (Some(_), None)                       => VersionOrder.Less
      case (None, Some(_))                       => VersionOrder.Greater
      case (None, None)                          => compareInt(left.compareTo(right), 0)

  private def compareInt(left: Int, right: Int): VersionOrder =
    if left > right then VersionOrder.Greater
    else if left < right then VersionOrder.Less
    else VersionOrder.Equal
