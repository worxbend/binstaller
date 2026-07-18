package binstaller.config

private[config] final case class DecodeResult[+A](value: A, errors: Vector[ValidationError]):
  def map[B](f: A => B): DecodeResult[B] = DecodeResult(f(value), errors)

private[config] object DecodeResult:
  def valid[A](value: A): DecodeResult[A] = DecodeResult(value, Vector.empty)

  def invalid[A](value: A, path: String, message: String): DecodeResult[A] =
    DecodeResult(value, Vector(ValidationError(path, message)))

  /** Collects sub-decode errors as a decoder walks its fields.
    *
    * A decoder gets each sub-value ONLY by handing its [[DecodeResult]] to [[apply]], which records
    * that result's errors and returns its value. This makes it impossible to read a sub-value while
    * forgetting to propagate its errors: there is no `.value` access that bypasses accumulation.
    * Errors are recorded in the exact order [[apply]] / [[report]] are called, so a decoder body
    * reproduces the previous hand-written `a.errors ++ b.errors ++ ...` order by invoking them in
    * that same left-to-right order.
    */
  final class Accumulator private[DecodeResult] ():
    private var collected: Vector[ValidationError] = Vector.empty

    /** Record `dr`'s errors and return its value. */
    def apply[B](dr: DecodeResult[B]): B =
      collected = collected ++ dr.errors
      dr.value

    /** Record raw errors produced outside a sub-decode (e.g. unknown-key or shape checks). */
    def report(errs: Vector[ValidationError]): Unit =
      collected = collected ++ errs

    private[DecodeResult] def collectedErrorsInOrder: Vector[ValidationError] = collected

  /** Build a value while automatically accumulating every sub-decode error the builder touches. */
  def accumulate[A](build: Accumulator => A): DecodeResult[A] =
    val accumulator = new Accumulator()
    val value       = build(accumulator)
    DecodeResult(value, accumulator.collectedErrorsInOrder)
