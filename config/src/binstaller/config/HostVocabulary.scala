package binstaller.config

import java.util.Locale

/**
 * The host names a `when` selector may use, and the canonical name each spelling stands for.
 *
 * The loader and the plan resolver read the same tables. With a copy on each side, a spelling the
 * loader accepts but the resolver cannot normalize would load and then match no host, which removes
 * the tool from every plan without a message.
 */
object HostVocabulary:

  // A manifest names exactly one spelling. Matching it by fragment let `linux, darwin` load and
  // mean linux alone, which left the entry out of every darwin plan without a message.
  private val osFamilySpellings: Vector[(String, Vector[String])] = Vector(
    "linux"   -> Vector("linux"),
    "darwin"  -> Vector("darwin", "macos", "mac", "osx"),
    "windows" -> Vector("windows")
  )

  // The JVM's `os.name` reads `Mac OS X` or `Windows 11`, so only the host's own name matches by
  // fragment.
  private val hostOsFamilyFragments: Vector[(String, Vector[String])] = Vector(
    "linux"   -> Vector("linux"),
    "darwin"  -> Vector("darwin", "mac"),
    "windows" -> Vector("windows")
  )

  private val architectureSpellings: Vector[(String, Vector[String])] = Vector(
    "amd64" -> Vector("amd64", "x86_64", "x64"),
    "arm64" -> Vector("arm64", "aarch64"),
    "386"   -> Vector("386", "x86", "i386", "i486", "i586", "i686")
  )

  /** What an operating-system family must be, for a validation message. */
  val osFamilyExpectation: String = s"one of ${listed(osFamilySpellings)}"

  /** What a CPU architecture must be, for a validation message. */
  val architectureExpectation: String = s"one of ${listed(architectureSpellings)}"

  /** Canonical operating-system family of a manifest spelling, or `None` for any other name. */
  def osFamily(value: String): Option[String] = canonical(osFamilySpellings, value)

  /** Canonical operating-system family of a host's `os.name`, or `None` for a name it lacks. */
  def hostOsFamily(value: String): Option[String] = osFamily(value).orElse:
    val name = normalized(value)
    hostOsFamilyFragments.collectFirst:
      case (family, fragments) if fragments.exists(name.contains) => family

  /** Canonical CPU architecture, or `None` for a name this program does not know. */
  def architecture(value: String): Option[String] = canonical(architectureSpellings, value)

  private def canonical(table: Vector[(String, Vector[String])], value: String): Option[String] =
    val name = normalized(value)
    table.collectFirst:
      case (canonicalName, spellings) if spellings.contains(name) => canonicalName

  private def normalized(value: String): String = value.trim.toLowerCase(Locale.ROOT)

  private def listed(names: Vector[(String, Vector[String])]): String =
    names.flatMap((_, accepted) => accepted).mkString(", ")
