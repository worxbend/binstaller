package binstaller.core

import binstaller.config.Diagnostics

import java.nio.file.InvalidPathException
import java.nio.file.Path
import ox.either.catching

/** Converts legacy string paths without letting malformed user input escape the typed boundary. */
private[core] object LegacyPathParser:

  def parse(rawPath: String): Either[InvalidLegacyPath, Path] = Path
    .of(rawPath)
    .catching[InvalidPathException]
    .left
    .map(error => InvalidLegacyPath(rawPath, Diagnostics.describe(error)))

private[core] final case class InvalidLegacyPath(path: String, message: String)
