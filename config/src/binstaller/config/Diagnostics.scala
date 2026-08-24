package binstaller.config

/**
 * Renders a throwable as a user-facing message that is never the literal "null".
 *
 * `Throwable.getMessage` is nullable, and several exceptions this program actually hits have no
 * message at all: `NullPointerException`, `IOException()` thrown from a closed stream, and a number
 * of `java.nio.file` and `java.net` constructions. Interpolating one of those straight into a
 * diagnostic produces user-facing output like `staging: null` or `download: <url>: null`, which
 * names neither what failed nor why.
 *
 * This lives in `config`, the lowest module, so `core` and `cli` can both use it without adding an
 * edge to the module graph.
 */
object Diagnostics:

  /**
   * Message text for `error`, falling back to its cause's message and finally to its class name.
   *
   * A present message is returned unchanged and unprefixed, so this only alters output in the case
   * where the alternative was the word "null".
   */
  def describe(error: Throwable): String = Option(error.getMessage)
    .orElse(Option(error.getCause).flatMap(cause => Option(cause.getMessage)))
    .getOrElse(error.getClass.getName)
