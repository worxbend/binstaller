package binstaller.core

import binstaller.config.Sha256Digest
import binstaller.config.ToolName

/** Smart-constructor and failure primitives every core test support trait builds on. */
private[core] trait CoreTestPrimitives:

  protected def abort(message: String): Nothing = throw java.lang.AssertionError(message)

  /** Parse a tool name literal, failing the test rather than the assertion on a typo. */
  protected def toolName(value: String): ToolName = ToolName.fromString(value) match
    case Right(name) => name
    case Left(error) => abort(s"invalid test tool name: $error")

  /** Matches a [[ToolName]] against its literal text, for pattern positions in event assertions. */
  protected object named:
    def unapply(name: ToolName): Some[String] = Some(name.value)

  /** Parse a hex literal into a digest, failing the test rather than the assertion on a typo. */
  protected def digest(hex: String): Sha256Digest = Sha256Digest.fromString(hex) match
    case Right(value) => value
    case Left(error)  => abort(s"invalid test digest: $error")

  /** Build a parallelism value through the smart constructor, failing the test on a bad literal. */
  protected def parallelism(value: Int): ApplyParallelism = ApplyParallelism.fromInt(value) match
    case Right(result) => result
    case Left(error)   => abort(s"invalid test parallelism: ${ApplyParallelismError.render(error)}")
