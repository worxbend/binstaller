package binstaller.core

import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The one place every path boundary checks path syntax, reported as a typed violation. */
private[core] object PathSyntaxRules:

  /** Syntactic path violations shared by every path boundary, in reporting order. */
  enum Violation:
    case Empty, ControlCharacters, Backslashes, DrivePrefixed, TraversalSegments

    /** Message fragment as consumed unprefixed by `RelativeInstallPath`. */
    def message: String = this match
      case Empty             => "must not be empty"
      case ControlCharacters => "must not contain control characters"
      case Backslashes       => "must not contain backslashes"
      case DrivePrefixed     => "must not be drive-prefixed"
      case TraversalSegments => "must not contain traversal segments"

  /** The first syntax violation in `value`, or `Right(())` when the syntax is clean. */
  def validate(value: String): Either[Violation, Unit] =
    if value.trim.isEmpty then Left(Violation.Empty)
    else if value.exists(Character.isISOControl) then Left(Violation.ControlCharacters)
    else if value.contains('\\') then Left(Violation.Backslashes)
    else if value.matches("^[A-Za-z]:.*") then Left(Violation.DrivePrefixed)
    else if hasTraversalSegment(value) then Left(Violation.TraversalSegments)
    else Right(())

  // Fail closed: a value the platform path parser cannot read is treated as traversal rather
  // than waved through.
  private def hasTraversalSegment(value: String): Boolean =
    Try(Path.of(value).iterator().asScala.exists(_.toString == "..")).getOrElse(true)
