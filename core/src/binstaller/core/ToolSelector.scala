package binstaller.core

private[core] object ToolSelector:

  def select(
      plan: ResolvedPlan,
      selection: ToolSelection
  ): Either[ResolvePlanError.SelectionFailed, ResolvedPlan] =
    // Selection input comes from the command line as raw text, so it stays untyped here and is
    // compared against the tool's name value. That keeps the "unknown tool '<raw>'" message
    // echoing exactly what the user typed.
    val toolNames = plan.tools.map(_.name.value).toSet
    val unknown   = (selection.only ++ selection.skip)
      .distinct
      .filterNot(toolNames.contains)
      .map(name => s"unknown tool '$name'")

    if unknown.nonEmpty then Left(ResolvePlanError.SelectionFailed(unknown))
    else Right(plan.copy(tools = selectedTools(plan.tools, selection)))

  private def selectedTools(
      tools: Vector[ResolvedTool],
      selection: ToolSelection
  ): Vector[ResolvedTool] =
    val onlyNames = selection.only.toSet
    val skipNames = selection.skip.toSet
    val included  =
      if onlyNames.isEmpty then tools
      else tools.filter(tool => onlyNames(tool.name.value))

    included.filterNot(tool => skipNames(tool.name.value))
