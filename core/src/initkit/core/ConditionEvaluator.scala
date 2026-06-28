package initkit.core

import initkit.config.{Condition, MatchExpression}
import initkit.host.HostFacts

object ConditionEvaluator:
  def evaluate(condition: Option[Condition], hostFacts: HostFacts): ConditionEvaluation =
    condition match
      case None        => ConditionEvaluation.matched
      case Some(value) => evaluate(value, hostFacts)

  def evaluate(condition: Condition, hostFacts: HostFacts): ConditionEvaluation =
    val reasons =
      evaluateOs(condition, hostFacts) ++
        evaluateCommandExists(condition, hostFacts)

    ConditionEvaluation.fromSkipReasons(reasons)

  private def evaluateOs(condition: Condition, hostFacts: HostFacts): Vector[ConditionSkipReason] =
    condition.os.toVector.flatMap: os =>
      Vector(
        os.family.flatMap(matchOsField("OS family", _, Some(hostFacts.os.family))),
        os.distribution.flatMap(matchOsField("distribution", _, hostFacts.os.distribution)),
        os.version.flatMap(matchOsField("OS version", _, hostFacts.os.version)),
        os.codename.flatMap(matchOsField("OS codename", _, hostFacts.os.codename)),
        os.architecture.flatMap(matchOsField("architecture", _, Some(hostFacts.architecture))),
        os.desktop.flatMap(matchOsField("desktop", _, None))
      ).flatten

  private def evaluateCommandExists(
      condition: Condition,
      hostFacts: HostFacts
  ): Vector[ConditionSkipReason] =
    condition.commandExists.toVector.flatMap: command =>
      Option.when(!hostFacts.commandExists(command))(ConditionSkipReason.MissingCommand(command))

  private def matchOsField(
      field: String,
      expected: MatchExpression,
      actual: Option[String]
  ): Option[ConditionSkipReason] =
    Option.when(!matches(expected, actual)):
      ConditionSkipReason.OsMismatch(field, ConditionExpectation.fromMatchExpression(expected), actual)

  private def matches(expected: MatchExpression, actual: Option[String]): Boolean =
    actual.exists: value =>
      expected match
        case MatchExpression.Exact(expectedValue) =>
          normalize(value) == normalize(expectedValue)
        case MatchExpression.OneOf(values) =>
          values.exists(expectedValue => normalize(value) == normalize(expectedValue))

  private def normalize(value: String): String =
    value.trim.toLowerCase

final case class ConditionEvaluation(
    matched: Boolean,
    skipReasons: Vector[ConditionSkipReason]
):
  def userFacingSkipReasons: Vector[String] =
    skipReasons.map(_.message)

object ConditionEvaluation:
  val matched: ConditionEvaluation =
    ConditionEvaluation(matched = true, skipReasons = Vector.empty)

  def fromSkipReasons(skipReasons: Vector[ConditionSkipReason]): ConditionEvaluation =
    ConditionEvaluation(matched = skipReasons.isEmpty, skipReasons = skipReasons)

enum ConditionSkipReason:
  case OsMismatch(field: String, expected: ConditionExpectation, actual: Option[String])
  case MissingCommand(command: String)

  def message: String =
    this match
      case OsMismatch(field, expected, Some(actual)) =>
        s"host $field is '$actual', expected ${expected.description}"
      case OsMismatch(field, expected, None) =>
        s"host $field is unavailable, expected ${expected.description}"
      case MissingCommand(command) =>
        s"required command '$command' is not available"

enum ConditionExpectation:
  case Exact(value: String)
  case OneOf(values: Vector[String])

  def description: String =
    this match
      case Exact(value) =>
        s"'$value'"
      case OneOf(values) =>
        values.map(value => s"'$value'").mkString("one of ", ", ", "")

object ConditionExpectation:
  def fromMatchExpression(expression: MatchExpression): ConditionExpectation =
    expression match
      case MatchExpression.Exact(value)  => ConditionExpectation.Exact(value)
      case MatchExpression.OneOf(values) => ConditionExpectation.OneOf(values)
