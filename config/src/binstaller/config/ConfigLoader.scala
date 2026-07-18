package binstaller.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

/** YAML loader used by [[ConfigModule]] and tests. */
object ConfigLoader:

  /** Read, parse, and validate a YAML profile from disk. */
  def load(path: Path): Either[ConfigLoadError, BinaryDistributionProfile] =
    Try(Files.readString(path)) match
      case Success(yaml)  => loadString(yaml)
      case Failure(error) => Left(ConfigLoadError.ReadFailed(path, error.getMessage))

  /** Parse and validate raw YAML profile text. */
  def loadString(yaml: String): Either[ConfigLoadError, BinaryDistributionProfile] =
    parseYaml(yaml).flatMap(loadParsedYaml)

  /** Bounds structural recursion so pathological nesting fails as a parse error, not a crash. */
  private val maxYamlDepth = 100

  private def parseYaml(yaml: String): Either[ConfigLoadError, Any] =
    val settings = LoadSettings.builder()
      .setLabel("binstaller-profile")
      .setAllowDuplicateKeys(false)
      .setAllowRecursiveKeys(false)
      .setAllowNonScalarKeys(false)
      .setMaxAliasesForCollections(50)
      .setCodePointLimit(2 * 1024 * 1024)
      .build()
    try convertYaml(Load(settings).loadFromString(yaml), 0)
        .left.map(ConfigLoadError.ParseFailed.apply)
    catch
      case error: YamlEngineException => Left(ConfigLoadError.ParseFailed(error.getMessage))
      // snakeyaml-engine 3.0.1 has no nesting-depth setting, so a deeply nested document can
      // overflow the parser's own recursion before convertYaml's depth guard is ever reached.
      // StackOverflowError is an Error, so Try/NonFatal would let it escape uncaught.
      case _: StackOverflowError =>
        Left(ConfigLoadError.ParseFailed("YAML document is too deeply nested to parse safely"))
      case NonFatal(error) => Left(ConfigLoadError.ParseFailed(error.getMessage))

  private def loadParsedYaml(value: Any): Either[ConfigLoadError, BinaryDistributionProfile] =
    val decoded = ManifestDecoder.decode(value)
    // Decode always yields sentinel fallbacks (""/empty) on failure, so ProfileValidator would
    // otherwise re-flag fields that already failed to decode (e.g. an empty tool name reported as
    // both "must not be empty" by decode and by the ToolName check). A wholesale
    // `if decoded.errors.nonEmpty then skip validate` is rejected: it would DROP the validator's
    // only errors for fields that decode accepts, such as traversal/control-character tool names
    // whose sole diagnostics come from ProfileValidator. Instead we run the validator and drop
    // only validator errors whose exact path already carries a decode error — the sole case where
    // the validator message is genuinely redundant. Paths the validator alone covers survive.
    // (An empty tool name may still legitimately surface via decode's "must not be empty"; only the
    // duplicate validator entry at that same path is suppressed.)
    val decodedPaths     = decoded.errors.map(_.path).toSet
    val validationErrors = ProfileValidator.validate(decoded.value)
      .filterNot(error => decodedPaths.contains(error.path))
    val errors = decoded.errors ++ validationErrors
    if errors.isEmpty then Right(decoded.value)
    else Left(ConfigLoadError.ValidationFailed(errors))

  private def convertYaml(value: Any, depth: Int): Either[String, Any] =
    if depth > maxYamlDepth then
      Left(s"YAML nesting exceeds the maximum supported depth of $maxYamlDepth")
    else
      value match
        case map: java.util.Map[?, ?] => map.asScala.toVector.foldLeft(
            Right(Map.empty): Either[String, Map[String, Any]]
          ):
            case (acc, (key: String, child)) =>
              for
                convertedMap   <- acc
                convertedChild <- convertYaml(child, depth + 1)
              yield convertedMap.updated(key, convertedChild)
            case (_, (key, _)) => Left(s"YAML mapping keys must be strings, found: $key")
        case list: java.util.List[?] => list.asScala.toVector.foldLeft(
            Right(Vector.empty): Either[String, Vector[Any]]
          ): (acc, child) =>
            for
              convertedList  <- acc
              convertedChild <- convertYaml(child, depth + 1)
            yield convertedList :+ convertedChild
        case scalar => Right(scalar)
