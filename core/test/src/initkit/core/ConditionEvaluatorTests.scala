package initkit.core

import initkit.config.{Condition, MatchExpression, OsCondition, RawYaml}
import initkit.host.HostFacts
import utest.*

object ConditionEvaluatorTests extends TestSuite:

  val tests: Tests = Tests:
    test("matches exact distribution condition"):
      val result = ConditionEvaluator.evaluate(
        condition(
          distribution = Some(MatchExpression.Exact("ubuntu"))
        ),
        HostFacts.fake(distribution = Some("ubuntu"))
      )

      assert(result.matched)
      assert(result.skipReasons.isEmpty)

    test("matches oneOf distribution condition"):
      val result = ConditionEvaluator.evaluate(
        condition(
          distribution = Some(MatchExpression.OneOf(Vector("debian", "ubuntu")))
        ),
        HostFacts.fake(distribution = Some("ubuntu"))
      )

      assert(result.matched)
      assert(result.userFacingSkipReasons.isEmpty)

    test("skips condition with user facing distribution mismatch reason"):
      val result = ConditionEvaluator.evaluate(
        condition(
          distribution = Some(MatchExpression.OneOf(Vector("debian", "ubuntu")))
        ),
        HostFacts.fake(distribution = Some("fedora"))
      )

      assert(!result.matched)
      assert(
        result.skipReasons == Vector(
          ConditionSkipReason.OsMismatch(
            field = "distribution",
            expected = ConditionExpectation.OneOf(Vector("debian", "ubuntu")),
            actual = Some("fedora")
          )
        )
      )
      assert(result.userFacingSkipReasons ==
        Vector("host distribution is 'fedora', expected one of 'debian', 'ubuntu'"))

    test("uses injected host facts for commandExists"):
      val result = ConditionEvaluator.evaluate(
        condition(commandExists = Some("systemctl")),
        HostFacts.fake(commands = Set("systemctl"))
      )

      assert(result.matched)

    test("skips missing command with user facing reason"):
      val result = ConditionEvaluator.evaluate(
        condition(commandExists = Some("systemctl")),
        HostFacts.fake(commands = Set.empty)
      )

      assert(!result.matched)
      assert(result.skipReasons == Vector(ConditionSkipReason.MissingCommand("systemctl")))
      assert(result.userFacingSkipReasons ==
        Vector("required command 'systemctl' is not available"))

  private def condition(
      family: Option[MatchExpression] = None,
      distribution: Option[MatchExpression] = None,
      commandExists: Option[String] = None
  ): Condition = Condition(
    os = Some(
      OsCondition(
        family = family,
        distribution = distribution,
        version = None,
        codename = None,
        architecture = None,
        desktop = None,
        raw = RawYaml.MappingValue(scala.collection.immutable.VectorMap.empty)
      )
    ),
    commandExists = commandExists,
    raw = RawYaml.MappingValue(scala.collection.immutable.VectorMap.empty)
  )
