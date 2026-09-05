package binstaller.core

import binstaller.config.BinaryDistributionProfile
import binstaller.config.ConfigLoadError
import binstaller.config.ConfigModule

/**
 * Boundary that turns a configured manifest location into a typed profile.
 *
 * Every other outward dependency of the installer service — the HTTP client, the state store, the
 * metadata client, the lock-file store — is injected. Manifest loading was the exception: it was a
 * direct filesystem call inside the orchestration logic, so exercising "resolve a plan and render
 * it" meant first writing YAML to a real directory.
 */
trait ProfileSource:

  /** Load and validate the profile identified by `configPath`. */
  def load(configPath: String): Either[ConfigLoadError, BinaryDistributionProfile]

  /** Load a typed profile input while preserving legacy file-source substitution. */
  def load(input: ProfileInput): Either[ConfigLoadError, BinaryDistributionProfile] = input match
    case ProfileInput.File(path) => load(path.toString)
    case ProfileInput.Yaml(text) => ConfigModule.loadString(text)

/** Constructors for production and test profile sources. */
object ProfileSource:

  /** Production source: reads and validates a YAML file from the filesystem. */
  def yamlFile: ProfileSource = configPath => ConfigModule.load(configPath)

  /** In-memory source: ignores the path and parses fixed YAML text. */
  def yamlText(yaml: String): ProfileSource = _ => ConfigModule.loadString(yaml)
