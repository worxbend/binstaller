package binstaller.core

/** Assertions over install outcomes: success shape and typed extraction failures. */
private[core] trait InstallResultAssertions extends CoreTestPrimitives:

  protected def assertInstallSuccess(
      result: Either[ToolInstallError, TerminalToolResult.Completed],
      installDir: String,
      expectedToolName: String = "alpha"
  ): Unit = result match
    case Right(success) =>
      assert(success.toolName.value == expectedToolName)
      assert(success.installDir == installDir)
    case Left(error) => abort(s"expected install success, got $error")

  /**
   * Assert extraction refused the archive with a message containing `fragment`. A mismatch names
   * both what was expected and what actually came back, which a bare pattern match cannot.
   */
  protected def assertArchiveExtractionFailed(
      result: Either[ToolInstallError, TerminalToolResult.Completed],
      fragment: String
  ): Unit =
    val expectation = s"expected extraction to fail with a message containing \"$fragment\""
    result match
      case Left(ToolInstallError.ArchiveExtractionFailed(_, message)) =>
        if !message.contains(fragment) then abort(s"$expectation, got \"$message\"")
      case Left(error)    => abort(s"$expectation, got $error")
      case Right(success) =>
        abort(s"$expectation, got a successful install at ${success.installDir}")
