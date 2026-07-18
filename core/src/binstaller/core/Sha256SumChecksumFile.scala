package binstaller.core

import scala.annotation.tailrec
import scala.util.matching.Regex

private[core] object Sha256SumChecksumFile:

  /** Outcome of looking up a filename in a sha256sum-style checksum file. */
  enum Lookup:
    case Found(hash: String)
    case NotFound
    case Ambiguous(paths: Vector[String])

  private val HashPattern: Regex = "(?i)^[0-9a-f]{64}$".r

  // Prefer an exact path match so a checksum file listing several platforms (e.g. `linux/tool` and
  // `darwin/tool`) never silently resolves the wrong entry for a requested basename. Only when no
  // exact path matches do we fall back to basename matching, and any real collision is surfaced as
  // Ambiguous instead of quietly picking the first line.
  def find(content: String, file: String): Lookup =
    val entries = content.linesIterator.flatMap(parseLine).toVector
    entries.filter((_, candidate) => candidate == file) match
      case Vector((hash, _)) => Lookup.Found(lower(hash))
      case Vector()          =>
        entries.filter((_, candidate) => fileName(candidate) == file) match
          case Vector((hash, _)) => Lookup.Found(lower(hash))
          case Vector()          => Lookup.NotFound
          case many              => Lookup.Ambiguous(many.map((_, candidate) => candidate))
      case many =>
        // Duplicate exact lines carrying the same digest are harmless; conflicting digests for the
        // same requested path must never be silently disambiguated.
        val hashes = many.map((hash, _) => lower(hash)).distinct
        if hashes.sizeIs == 1 then Lookup.Found(hashes.head)
        else Lookup.Ambiguous(many.map((_, candidate) => candidate))

  private def parseLine(line: String): Option[(String, String)] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then None
    else
      // GNU coreutils prefixes a line with `\` when the filename contains a backslash or newline,
      // escaping those characters in the filename token that follows the digest.
      val escaped = trimmed.startsWith("\\")
      val body    = if escaped then trimmed.drop(1) else trimmed
      body.split("\\s+", 2).toVector match
        case Vector(hash, path) if HashPattern.pattern.matcher(hash).matches() =>
          val name = path.stripPrefix("*").trim
          Some(hash -> (if escaped then unescape(name) else name))
        case _ => None

  private def unescape(value: String): String =
    @tailrec
    def loop(index: Int, acc: List[Char]): String =
      if index >= value.length then acc.reverse.mkString
      else if value.charAt(index) == '\\' && index + 1 < value.length then
        value.charAt(index + 1) match
          case '\\'  => loop(index + 2, '\\' :: acc)
          case 'n'   => loop(index + 2, '\n' :: acc)
          case 'r'   => loop(index + 2, '\r' :: acc)
          case _     => loop(index + 1, '\\' :: acc)
      else loop(index + 1, value.charAt(index) :: acc)
    loop(0, Nil)

  private def lower(hash: String): String = hash.toLowerCase(java.util.Locale.ROOT)

  private def fileName(path: String): String =
    path.split('/').toVector.filter(_.nonEmpty).lastOption.getOrElse(path)
