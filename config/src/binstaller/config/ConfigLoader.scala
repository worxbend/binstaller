package binstaller.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.CollectionStartEvent
import org.snakeyaml.engine.v2.events.ScalarEvent
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.resolver.CoreScalarResolver
import org.snakeyaml.engine.v2.resolver.ScalarResolver
import org.snakeyaml.engine.v2.schema.CoreSchema

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try
import scala.util.Using
import scala.util.control.NonFatal

/** YAML loader used by [[ConfigModule]] and tests. */
object ConfigLoader:

  /** Read, parse, and validate a YAML profile from disk. */
  def load(path: Path): Either[ConfigLoadError, BinaryDistributionProfile] =
    readConfigText(path).left.map(ConfigLoadError.ReadFailed(path, _)).flatMap(loadString)

  /** Parse and validate raw YAML profile text. */
  def loadString(yaml: String): Either[ConfigLoadError, BinaryDistributionProfile] =
    withinTextLimits(yaml).flatMap(parseYaml).flatMap(loadParsedYaml)

  /** Bounds structural recursion so pathological nesting fails as a parse error, not a crash. */
  private val maxYamlDepth = 100

  private val maxYamlCodePoints = 2 * 1024 * 1024

  /** No profile needs a longer line; one long number scalar alone takes the parser minutes. */
  private val maxYamlLineCodePoints = 64 * 1024

  /**
   * A code point is at most four UTF-8 bytes, so a larger file cannot pass the code-point limit.
   */
  private val maxConfigFileBytes = 4 * maxYamlCodePoints

  /** A document cannot hold more nodes than code points; more means aliases are expanding it. */
  private val maxYamlNodes = maxYamlCodePoints

  // Stops reading one byte past the limit: the path may name a disk image, a device or a FIFO,
  // and reading one of those whole ends in an OutOfMemoryError that no handler here catches.
  private def readConfigText(path: Path): Either[String, String] =
    val attempt = Try:
      val bytes = Using.resource(Files.newInputStream(path))(_.readNBytes(maxConfigFileBytes + 1))
      if bytes.length > maxConfigFileBytes then
        Left(s"config file is larger than $maxConfigFileBytes bytes")
      else Right(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString)
    attempt.fold(error => Left(Diagnostics.describeReadFailure(error)), identity)

  // snakeyaml checks its code-point limit only when it fetches the next token, so one long token
  // is scanned in full, in quadratic time, before any limit applies.
  private def withinTextLimits(yaml: String): Either[ConfigLoadError, String] =
    if codePoints(yaml) > maxYamlCodePoints then
      Left(ConfigLoadError.ParseFailed(
        s"YAML document exceeds the limit of $maxYamlCodePoints code points"
      ))
    else
      firstOverlongLine(yaml) match
        case Some(line) => Left(ConfigLoadError.ParseFailed(
            s"YAML line $line exceeds the limit of $maxYamlLineCodePoints characters"
          ))
        case None => Right(yaml)

  // String.lines splits on LF, CR and CRLF, the same line breaks YAML counts.
  private def firstOverlongLine(yaml: String): Option[Int] =
    yaml.lines().iterator().asScala.zipWithIndex.collectFirst:
      case (line, index) if codePoints(line) > maxYamlLineCodePoints => index + 1

  private def codePoints(text: String): Int = text.codePointCount(0, text.length)

  // Core scalars without merge keys. A merge copies its source inside snakeyaml, before the node
  // budget counts anything, and a mapping that merges itself loops there forever.
  private val mergeFreeCoreResolver: ScalarResolver = CoreScalarResolver(false)

  private val mergeFreeCoreSchema: CoreSchema = new CoreSchema:
    override def getScalarResolver: ScalarResolver = mergeFreeCoreResolver

  private def loadSettings: LoadSettings = LoadSettings.builder()
    .setLabel("binstaller-profile")
    // The library default is the JSON schema, where `~` and `Null` are strings: `stateFile: ~`
    // named a state file `~` and `versions: {tool: ~}` put `~` into the download URL.
    .setSchema(mergeFreeCoreSchema)
    .setAllowDuplicateKeys(false)
    .setAllowRecursiveKeys(false)
    .setAllowNonScalarKeys(false)
    .setMaxAliasesForCollections(50)
    .setCodePointLimit(maxYamlCodePoints)
    .build()

  private def parseYaml(yaml: String): Either[ConfigLoadError, Any] =
    val settings = loadSettings
    try
      if hasMergeTag(settings, yaml) then
        Left(ConfigLoadError.ParseFailed("YAML merge keys are not supported"))
      else
        YamlConversion().convert(Load(settings).loadFromString(yaml), 0)
          .left.map(ConfigLoadError.ParseFailed.apply)
    catch
      case error: YamlEngineException =>
        Left(ConfigLoadError.ParseFailed(Diagnostics.describe(error)))
      // snakeyaml-engine 3.0.1 has no nesting-depth setting, so a deeply nested document can
      // overflow the parser's own recursion before convertYaml's depth guard is ever reached.
      // StackOverflowError is an Error, so Try/NonFatal would let it escape uncaught.
      case _: StackOverflowError =>
        Left(ConfigLoadError.ParseFailed("YAML document is too deeply nested to parse safely"))
      case NonFatal(error) => Left(ConfigLoadError.ParseFailed(Diagnostics.describe(error)))

  // The composer merges on an explicit merge tag whatever the resolver says about `<<`. The parser
  // has already expanded tag handles, so `!!merge` and a `%TAG` shorthand arrive the same way.
  private def hasMergeTag(settings: LoadSettings, yaml: String): Boolean =
    Parse(settings).parseString(yaml).asScala.exists:
      case event: ScalarEvent          => event.getTag.toScala.contains(Tag.MERGE.getValue)
      case event: CollectionStartEvent => event.getTag.toScala.contains(Tag.MERGE.getValue)
      case _                           => false

  private def loadParsedYaml(value: Any): Either[ConfigLoadError, BinaryDistributionProfile] =
    val decoded = ManifestDecoder.decode(value)
    // An unknown key left no placeholder behind, and its path is the key as the user spelled it:
    // `$` or `plan[0]` would read as a failed block and hide the validator's real errors.
    val failedPaths = decoded.errors.filterNot(ManifestDecoder.isUnknownField).map(_.path).toSet
    val errors      = decoded.errors ++ ProfileValidator.validate(decoded.value, failedPaths)
    if errors.isEmpty then Right(decoded.value)
    else Left(ConfigLoadError.ValidationFailed(errors))

  /**
   * One conversion pass over a parsed document, bounded by a node budget.
   *
   * snakeyaml hands every `*alias` the collection its anchor produced, and conversion copies that
   * collection at each reference. Nested aliases therefore multiply: a few hundred bytes, well
   * inside the alias cap, would otherwise expand until the heap is gone.
   */
  private final class YamlConversion:
    private var convertedNodes = 0

    def convert(value: Any, depth: Int): Either[String, Any] =
      convertedNodes += 1
      if depth > maxYamlDepth then
        Left(s"YAML nesting exceeds the maximum supported depth of $maxYamlDepth")
      else if convertedNodes > maxYamlNodes then
        Left(s"YAML aliases expand the document past the supported $maxYamlNodes nodes")
      else
        value match
          case map: java.util.Map[?, ?] => map.asScala.toVector.foldLeft(
              Right(Map.empty): Either[String, Map[String, Any]]
            ):
              case (acc, (key: String, child)) =>
                for
                  convertedMap   <- acc
                  convertedChild <- convert(child, depth + 1)
                yield convertedMap.updated(key, convertedChild)
              case (_, (key, _)) => Left(s"YAML mapping keys must be strings, found: $key")
          case list: java.util.List[?] => list.asScala.toVector.foldLeft(
              Right(Vector.empty): Either[String, Vector[Any]]
            ): (acc, child) =>
              for
                convertedList  <- acc
                convertedChild <- convert(child, depth + 1)
              yield convertedList :+ convertedChild
          case scalar => Right(scalar)
