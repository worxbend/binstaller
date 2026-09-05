package binstaller.core

/** Inputs needed to resolve a selected plan without rendering or writing. */
final case class PlanRequest(
    profile: ProfileInput,
    selection: ToolSelection = ToolSelection.all
)
