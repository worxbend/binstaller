package initkit.config

import java.nio.file.Path

object ManifestValidator:
  private val SupportedApiVersion = "initkit.io/v1alpha1"
  private val SupportedKind = "WorkstationProfile"
  private val DefaultExecutionMode = "sequential"
  private val SupportedExecutionModes = Set("sequential", "parallel")
  private val SupportedPlanKinds = PackageSpecDecoder.PackageKinds ++ InstallerSpecDecoder.InstallerKinds

  def validate(manifest: Manifest, manifestPath: Option[Path] = None): Either[Vector[ManifestValidationError], Manifest] =
    val errors =
      validateTopLevel(manifest) ++
        validatePlanEntries(manifest.spec.plan, manifestPath)

    if errors.isEmpty then Right(manifest)
    else Left(errors)

  private def validateTopLevel(manifest: Manifest): Vector[ManifestValidationError] =
    validateRequiredFixedValue(manifest.apiVersion, "apiVersion", SupportedApiVersion, "apiVersion") ++
      validateRequiredFixedValue(manifest.kind, "kind", SupportedKind, "kind")

  private def validatePlanEntries(
      plan: Vector[PlanEntry],
      manifestPath: Option[Path]
  ): Vector[ManifestValidationError] =
    plan.zipWithIndex.flatMap((entry, index) => validatePlanEntry(entry, index, manifestPath)) ++
      validateDuplicatePlanNames(plan)

  private def validatePlanEntry(
      entry: PlanEntry,
      index: Int,
      manifestPath: Option[Path]
  ): Vector[ManifestValidationError] =
    val kind = entry.kind.flatMap(nonEmptyTrimmed)

    validateRequiredString(entry.name, planPath(index, "name")) ++
      validatePlanKind(entry.kind, planPath(index, "kind")) ++
      validateExecution(entry.execution, planPath(index, "execution")) ++
      kind.toVector.flatMap(validateKindSpecificSpec(entry, index, _, manifestPath))

  private def validatePlanKind(value: Option[String], at: String): Vector[ManifestValidationError] =
    value.flatMap(nonEmptyTrimmed) match
      case None => Vector(error(at, "is required"))
      case Some(kind) if SupportedPlanKinds.contains(kind) => Vector.empty
      case Some(kind) => Vector(error(at, s"unsupported plan kind '$kind'"))

  private def validateKindSpecificSpec(
      entry: PlanEntry,
      index: Int,
      kind: String,
      manifestPath: Option[Path]
  ): Vector[ManifestValidationError] =
    val specAt = planPath(index, "spec")

    entry.spec match
      case None => Vector(error(specAt, "is required"))
      case Some(RawYaml.MappingValue(_)) =>
        kind match
          case packageKind if PackageSpecDecoder.isPackageKind(packageKind) =>
            PackageSpecDecoder.decode(packageKind, entry.spec, specAt, entry.name).left.toOption.getOrElse(Vector.empty)
          case installerKind if InstallerSpecDecoder.isInstallerKind(installerKind) =>
            InstallerSpecDecoder
              .decode(installerKind, entry.spec, specAt, entry.name, manifestPath)
              .left
              .toOption
              .getOrElse(Vector.empty)
          case _ => Vector.empty
      case Some(other) => Vector(error(specAt, s"must be a mapping, found ${kindOf(other)}"))

  private def validateExecution(
      execution: Option[Execution],
      at: String
  ): Vector[ManifestValidationError] =
    execution match
      case None => Vector.empty
      case Some(value) =>
        val explicitMode = value.mode.map(_.trim)
        val modeForRules = explicitMode.filter(_.nonEmpty).getOrElse(DefaultExecutionMode)

        validateExecutionMode(explicitMode, s"$at.mode") ++
          validateMaxConcurrency(value.maxConcurrency, modeForRules, s"$at.maxConcurrency")

  private def validateExecutionMode(
      mode: Option[String],
      at: String
  ): Vector[ManifestValidationError] =
    mode match
      case None => Vector.empty
      case Some(value) if value.isEmpty => Vector(error(at, "must not be empty"))
      case Some(value) if SupportedExecutionModes.contains(value) => Vector.empty
      case Some(value) => Vector(error(at, s"unsupported execution mode '$value'"))

  private def validateMaxConcurrency(
      value: Option[Int],
      mode: String,
      at: String
  ): Vector[ManifestValidationError] =
    value match
      case Some(maxConcurrency) if maxConcurrency < 1 => Vector(error(at, "must be at least 1"))
      case Some(maxConcurrency) if mode == DefaultExecutionMode && maxConcurrency > 1 =>
        Vector(error(at, "can only be greater than 1 when execution.mode is parallel"))
      case _ => Vector.empty

  private def validateDuplicatePlanNames(plan: Vector[PlanEntry]): Vector[ManifestValidationError] =
    val namedEntries = plan.zipWithIndex.flatMap { case (entry, index) =>
      entry.name.flatMap(nonEmptyTrimmed).map(name => name -> index)
    }

    namedEntries
      .foldLeft((Map.empty[String, Int], Vector.empty[ManifestValidationError])) {
        case ((seen, errors), (name, index)) =>
          seen.get(name) match
            case Some(firstIndex) =>
              val detail = s"duplicate plan name '$name' also used at ${planPath(firstIndex, "name")}"
              (seen, errors :+ error(planPath(index, "name"), detail))
            case None => (seen.updated(name, index), errors)
      }
      ._2

  private def validateRequiredFixedValue(
      value: Option[String],
      fieldName: String,
      expected: String,
      at: String
  ): Vector[ManifestValidationError] =
    value.flatMap(nonEmptyTrimmed) match
      case None => Vector(error(at, s"$fieldName is required"))
      case Some(actual) if actual == expected => Vector.empty
      case Some(actual) => Vector(error(at, s"unsupported $fieldName '$actual'; expected '$expected'"))

  private def validateRequiredString(
      value: Option[String],
      at: String
  ): Vector[ManifestValidationError] =
    value match
      case Some(text) if text.trim.nonEmpty => Vector.empty
      case Some(_)                         => Vector(error(at, "must not be empty"))
      case None                            => Vector(error(at, "is required"))

  private def nonEmptyTrimmed(value: String): Option[String] =
    val trimmed = value.trim
    Option.when(trimmed.nonEmpty)(trimmed)

  private def planPath(index: Int, field: String): String =
    s"spec.plan[$index].$field"

  private def error(path: String, detail: String): ManifestValidationError =
    ManifestValidationError(path, detail)

  private def kindOf(value: RawYaml): String =
    value match
      case RawYaml.NullValue         => "null"
      case RawYaml.StringValue(_)    => "string"
      case RawYaml.BooleanValue(_)   => "boolean"
      case RawYaml.IntegerValue(_)   => "integer"
      case RawYaml.DecimalValue(_)   => "decimal"
      case RawYaml.SequenceValue(_)  => "sequence"
      case RawYaml.MappingValue(_)   => "mapping"
