package binstaller.core

import binstaller.config.ToolName

import upickle.default.*

/** JSON codec for [[ToolName]] in the apply-state and lock files.
 *
 *  Serializes as a bare string, so both file formats are unchanged. Reading goes back through
 *  `fromString`, which means a hand-edited state or lock file naming a tool `../evil` now fails to
 *  decode rather than being loaded and used as a path segment.
 *
 *  It lives in `core` rather than beside the type so that `config` does not need a dependency on
 *  upickle for the benefit of two files it never reads.
 */
private[core] object ToolNameCodec:

  given ReadWriter[ToolName] = readwriter[String].bimap[ToolName](
    _.value,
    value =>
      ToolName.fromString(value).fold(
        message => throw upickle.core.Abort(s"invalid tool name '$value': $message"),
        identity
      )
  )
