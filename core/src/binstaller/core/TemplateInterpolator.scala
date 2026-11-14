package binstaller.core

import binstaller.config.ValidationError

import scala.util.matching.Regex

private[core] object TemplateInterpolator:
  private val Variable: Regex = "\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}".r
  // Anything that still opens a placeholder once every known variable is substituted.
  private val LeftoverPlaceholder: Regex = "\\$\\{[^}]*\\}?".r

  def interpolate(
      value: String,
      path: String,
      vars: Map[String, String]
  ): ResolvedValue[String] =
    // Only ${name} placeholders are recognized. Shell forms such as $(...) remain literal data.
    val errors = variableNames(value).distinct.flatMap: name =>
      if vars.contains(name) then Vector.empty
      else Vector(ValidationError(path, s"unresolved variable '$name'"))

    val rendered = Variable.replaceAllIn(
      value,
      matched => Regex.quoteReplacement(vars.getOrElse(matched.group(1), matched.matched))
    )
    ResolvedValue(rendered, if errors.nonEmpty then errors else leftoverErrors(rendered, path))

  // Checked only when every referenced name resolved: an unresolved name stays in the output and
  // is already reported. What remains is a name the pattern cannot match, such as `${linux-arch}`,
  // or a variable whose own value was a template. Either would reach the filesystem verbatim.
  private def leftoverErrors(rendered: String, path: String): Vector[ValidationError] =
    LeftoverPlaceholder.findFirstIn(rendered).toVector.map: placeholder =>
      ValidationError(
        path,
        s"placeholder '$placeholder' was not resolved: variable names use letters, digits and " +
          "'_', and a spec.vars entry cannot reference another entry that is itself a template"
      )

  def variableNames(value: String): Vector[String] =
    Variable.findAllMatchIn(value).map(_.group(1)).toVector
