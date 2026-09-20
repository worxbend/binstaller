package binstaller.core

import binstaller.config.BinaryDistributionProfile
import binstaller.config.ConfigLoadError
import binstaller.config.ConfigModule

import java.nio.file.Path

/**
 * Boundary that turns a configured manifest location into a typed profile.
 *
 * Every other outward dependency of the installer service — the HTTP client, the state store, the
 * metadata client, the lock-file store — is injected. Manifest loading was the exception: it was a
 * direct filesystem call inside the orchestration logic, so exercising "resolve a plan and render
 * it" meant first writing YAML to a real directory.
 *
 * The single abstract method takes [[ProfileInput]], so both the file and inline-YAML load paths
 * cross the injected seam; the string overload only adapts the legacy call shape.
 */
trait ProfileSource:

  /** Load and validate the profile identified by `configPath`. */
  def load(configPath: String): Either[ConfigLoadError, BinaryDistributionProfile] =
    load(ProfileInput.File(Path.of(configPath)))

  /** Load a typed profile input. */
  def load(input: ProfileInput): Either[ConfigLoadError, BinaryDistributionProfile]

/** Constructors for production and test profile sources. */
object ProfileSource:

  /** Production source: reads and validates YAML from the filesystem or from memory. */
  def yamlFile: ProfileSource =
    case ProfileInput.File(path) => ConfigModule.load(path.toString)
    case ProfileInput.Yaml(text) => ConfigModule.loadString(text)

  /** In-memory source: file inputs parse fixed YAML text, ignoring the path. */
  def yamlText(yaml: String): ProfileSource =
    case ProfileInput.File(_)    => ConfigModule.loadString(yaml)
    case ProfileInput.Yaml(text) => ConfigModule.loadString(text)
