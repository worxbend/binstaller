package binstaller.core

import binstaller.config.ArchiveSpec
import binstaller.config.BinaryDistributionProfile
import binstaller.config.BinaryToolSpec
import binstaller.config.ChecksumSpec
import binstaller.config.ConfigLoadError
import binstaller.config.ConfigModule
import binstaller.config.DownloadSpec
import binstaller.config.DynamicVersionKind
import binstaller.config.ExecutableMode
import binstaller.config.ExtractMapping
import binstaller.config.InstallerEnv
import binstaller.config.InstallerShell
import binstaller.config.InstallerSpec
import binstaller.config.PlanEntry
import binstaller.config.SymlinkPrivilege
import binstaller.config.ValidationError
import binstaller.config.VersionResolverKind
import binstaller.config.VersionSource

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.matching.Regex

object CoreModule:
  def modulePath: Vector[String] = Vector(ConfigModule.moduleName, "core")

enum ResetState:
  case Enabled, Disabled

object ResetState:
  def fromFlag(value: Boolean): ResetState = if value then Enabled else Disabled

enum VerboseOutput:
  case Enabled, Disabled

object VerboseOutput:
  def fromFlag(value: Boolean): VerboseOutput = if value then Enabled else Disabled

final case class InstallerOptions(
    configPath: String,
    statePath: Option[String],
    resetState: ResetState,
    verboseOutput: VerboseOutput,
    selection: ToolSelection = ToolSelection.all,
    dryRun: DryRunMode = DryRunMode.Disabled
)

final case class InstallerResult(lines: Vector[String], exitCode: Int)

final case class ToolSelection(only: Vector[String], skip: Vector[String])

object ToolSelection:
  def all: ToolSelection = ToolSelection(Vector.empty, Vector.empty)

enum DryRunMode:
  case Enabled, Disabled

object DryRunMode:
  def fromFlag(value: Boolean): DryRunMode = if value then Enabled else Disabled

trait BinaryInstallerService:
  def plan(options: InstallerOptions): InstallerResult
  def apply(options: InstallerOptions): InstallerResult
  def versions(options: InstallerOptions): InstallerResult

object BinaryInstallerService:
  def placeholder: BinaryInstallerService = PlaceholderBinaryInstallerService

  def resolving(httpTextClient: HttpTextClient): BinaryInstallerService =
    ResolvingBinaryInstallerService(httpTextClient, ResolutionOptions.fromEnvironment())

final case class ResolutionOptions(runtimeVariables: Map[String, String])

object ResolutionOptions:
  def fromEnvironment(): ResolutionOptions = ResolutionOptions(sys.env.toMap)

final case class HttpTextError(url: String, message: String)

trait HttpTextClient:
  def getText(url: String): Either[HttpTextError, String]

object HttpTextClient:
  def jdk: HttpTextClient = JdkHttpTextClient(HttpClient.newHttpClient())

final case class ResolvedPlan(
    policy: ResolvedPolicy,
    tools: Vector[ResolvedTool]
)

final case class ResolvedPolicy(appsDir: String, stateFile: Option[String])

final case class ResolvedTool(
    name: String,
    description: Option[String],
    version: ResolvedVersion,
    installDir: String,
    createDirectories: Vector[String],
    download: ResolvedDownload,
    installer: Option[ResolvedInstaller],
    executables: Vector[ResolvedExecutable],
    symlinks: Vector[ResolvedSymlink]
)

enum ResolvedVersion:
  case Concrete(value: String)
  case DynamicLatestUrl(note: Option[String])

object ResolvedVersion:

  def render(version: ResolvedVersion): String = version match
    case ResolvedVersion.Concrete(value)     => value
    case ResolvedVersion.DynamicLatestUrl(_) => "dynamic latest-url"

final case class ResolvedDownload(
    url: String,
    filename: String,
    checksum: Option[ChecksumSpec],
    archive: Option[ResolvedArchive]
)

final case class ResolvedArchive(
    original: ArchiveSpec,
    files: Vector[ResolvedExtractMapping],
    directories: Vector[ResolvedExtractMapping]
)

final case class ResolvedExtractMapping(from: String, to: String)

final case class ResolvedInstaller(
    shell: InstallerShell,
    args: Vector[String],
    env: Vector[ResolvedInstallerEnv],
    cleanup: Boolean
)

final case class ResolvedInstallerEnv(name: String, value: String)

final case class ResolvedExecutable(path: String, mode: Option[ExecutableMode])

final case class ResolvedSymlink(path: String, target: String, privilege: SymlinkPrivilege)

enum ResolvePlanError:
  case ConfigLoadFailed(error: ConfigLoadError)
  case ValidationFailed(errors: Vector[ValidationError])
  case SelectionFailed(messages: Vector[String])

object PlanResolver:

  def resolve(
      profile: BinaryDistributionProfile,
      options: ResolutionOptions,
      httpTextClient: HttpTextClient
  ): Either[ResolvePlanError.ValidationFailed, ResolvedPlan] =
    val resolved = ResolutionBuilder(profile, options, httpTextClient).resolve()
    if resolved.errors.isEmpty then Right(resolved.value)
    else Left(ResolvePlanError.ValidationFailed(resolved.errors))

private object PlaceholderBinaryInstallerService extends BinaryInstallerService:
  def plan(options: InstallerOptions): InstallerResult = placeholderResult("plan", options)

  def apply(options: InstallerOptions): InstallerResult = placeholderResult("apply", options)

  def versions(options: InstallerOptions): InstallerResult = placeholderResult("versions", options)

  private def placeholderResult(command: String, options: InstallerOptions): InstallerResult =
    InstallerResult(
      Vector(s"binstaller $command placeholder for ${options.configPath}"),
      0
    )

private final class ResolvingBinaryInstallerService(
    httpTextClient: HttpTextClient,
    resolutionOptions: ResolutionOptions
) extends BinaryInstallerService:

  def plan(options: InstallerOptions): InstallerResult =
    renderSelectedPlan(options, PlanRenderCommand.Plan)

  def apply(options: InstallerOptions): InstallerResult =
    if options.dryRun == DryRunMode.Enabled then
      renderSelectedPlan(options, PlanRenderCommand.ApplyDryRun)
    else
      InstallerResult(
        Vector("apply execution is not implemented yet"),
        1
      )

  def versions(options: InstallerOptions): InstallerResult =
    resolveFromOptions(options).fold(renderError, renderVersions)

  private def renderSelectedPlan(
      options: InstallerOptions,
      command: PlanRenderCommand
  ): InstallerResult =
    resolveSelectedPlan(options).fold(renderError, plan => PlanRenderer.render(plan, command))

  private def resolveSelectedPlan(
      options: InstallerOptions
  ): Either[ResolvePlanError, ResolvedPlan] =
    resolveFromOptions(options).flatMap(plan => ToolSelector.select(plan, options.selection))

  private def resolveFromOptions(
      options: InstallerOptions
  ): Either[ResolvePlanError, ResolvedPlan] = ConfigModule.load(options.configPath) match
    case Left(error)    => Left(ResolvePlanError.ConfigLoadFailed(error))
    case Right(profile) => PlanResolver.resolve(profile, resolutionOptions, httpTextClient)

  private def renderVersions(plan: ResolvedPlan): InstallerResult = InstallerResult(
    plan.tools.map(tool => s"${tool.name} ${ResolvedVersion.render(tool.version)}"),
    0
  )

  private def renderError(error: ResolvePlanError): InstallerResult = error match
    case ResolvePlanError.ConfigLoadFailed(loadError) =>
      InstallerResult(Vector(s"config load failed: $loadError"), 1)
    case ResolvePlanError.ValidationFailed(errors) =>
      InstallerResult(errors.map(error => s"${error.path}: ${error.message}"), 1)
    case ResolvePlanError.SelectionFailed(messages) =>
      InstallerResult(messages.map(message => s"selection: $message"), 1)

private object ToolSelector:

  def select(
      plan: ResolvedPlan,
      selection: ToolSelection
  ): Either[ResolvePlanError.SelectionFailed, ResolvedPlan] =
    val toolNames = plan.tools.map(_.name).toSet
    val unknown   = (selection.only ++ selection.skip)
      .distinct
      .filterNot(toolNames.contains)
      .map(name => s"unknown tool '$name'")

    if unknown.nonEmpty then Left(ResolvePlanError.SelectionFailed(unknown))
    else Right(plan.copy(tools = selectedTools(plan.tools, selection)))

  private def selectedTools(
      tools: Vector[ResolvedTool],
      selection: ToolSelection
  ): Vector[ResolvedTool] =
    val onlyNames = selection.only.toSet
    val skipNames = selection.skip.toSet
    val included  =
      if onlyNames.isEmpty then tools
      else tools.filter(tool => onlyNames(tool.name))

    included.filterNot(tool => skipNames(tool.name))

private enum PlanRenderCommand:
  case Plan, ApplyDryRun

private object PlanRenderer:

  def render(plan: ResolvedPlan, command: PlanRenderCommand): InstallerResult =
    InstallerResult(header(plan, command) ++ plan.tools.zipWithIndex.flatMap(renderTool), 0)

  private def header(plan: ResolvedPlan, command: PlanRenderCommand): Vector[String] =
    val sudoSymlinkCount =
      plan.tools.flatMap(_.symlinks).count(_.privilege == SymlinkPrivilege.Sudo)
    val stateLine = plan.policy.stateFile match
      case Some(path) => s"state file: $path (not created)"
      case None       => "state file: not configured"
    val sudoLine =
      if sudoSymlinkCount == 0 then "sudo risk: none"
      else
        s"sudo risk: YES - $sudoSymlinkCount sudo symlink command(s) require elevated privileges"
    val title = command match
      case PlanRenderCommand.Plan        => "binstaller plan (dry-run)"
      case PlanRenderCommand.ApplyDryRun => "binstaller apply --dry-run"

    Vector(
      title,
      s"tools: ${plan.tools.size}",
      s"apps dir: ${plan.policy.appsDir} (not created)",
      stateLine,
      "filesystem: no changes will be made",
      sudoLine
    )

  private def renderTool(indexedTool: (ResolvedTool, Int)): Vector[String] =
    val (tool, index) = indexedTool
    Vector(
      "",
      s"${index + 1}. ${tool.name}",
      s"   destination: ${tool.installDir}",
      s"   version: ${renderVersion(tool.version)}",
      s"   download: ${tool.download.url}",
      s"   download file: ${joinPath(tool.installDir, tool.download.filename)}",
      s"   checksum: ${renderChecksum(tool.download.checksum)}"
    ) ++ renderCreateDirectories(tool) ++ renderStrategy(tool) ++ renderExecutables(tool) ++
      renderSymlinks(tool)

  private def renderVersion(version: ResolvedVersion): String = version match
    case ResolvedVersion.Concrete(value)     => s"concrete $value"
    case ResolvedVersion.DynamicLatestUrl(_) => "dynamic latest-url"

  private def renderChecksum(checksum: Option[ChecksumSpec]): String = checksum match
    case Some(value) => s"${value.algorithm.value} ${value.value}"
    case None        => "not configured"

  private def renderCreateDirectories(tool: ResolvedTool): Vector[String] =
    if tool.createDirectories.isEmpty then Vector.empty
    else
      Vector("   create directories:") ++
        tool.createDirectories.map(path => s"     ${joinPath(tool.installDir, path)}")

  private def renderStrategy(tool: ResolvedTool): Vector[String] =
    val archiveLines = tool.download.archive match
      case Some(archive) => renderArchive(archive)
      case None          => Vector("   archive: none")
    val installerLines = tool.installer match
      case Some(installer) => renderInstaller(installer)
      case None            => Vector("   installer: none")
    val directLine =
      if tool.download.archive.isEmpty && tool.installer.isEmpty then
        Vector("   strategy: direct binary download")
      else Vector.empty

    directLine ++ archiveLines ++ installerLines

  private def renderArchive(archive: ResolvedArchive): Vector[String] =
    Vector(s"   archive: ${archive.original.archiveType.value}") ++
      archive.files.map(mapping => s"     file ${mapping.from} -> ${mapping.to}") ++
      archive.directories.map(mapping => s"     directory ${mapping.from} -> ${mapping.to}")

  private def renderInstaller(installer: ResolvedInstaller): Vector[String] = Vector(
    s"   installer: ${installer.shell.value} ${installer.args.map(shellQuote).mkString(" ")}"
  ) ++
    installer.env.map(env => s"     env ${env.name}=${shellQuote(env.value)}") :+
    s"     cleanup: ${installer.cleanup}"

  private def renderExecutables(tool: ResolvedTool): Vector[String] =
    if tool.executables.isEmpty then Vector("   executables: none")
    else
      Vector("   executables:") ++ tool.executables.map: executable =>
        val mode = executable.mode.map(value => s" mode ${value.value}").getOrElse("")
        s"     ${joinPath(tool.installDir, executable.path)}$mode"

  private def renderSymlinks(tool: ResolvedTool): Vector[String] =
    if tool.symlinks.isEmpty then Vector("   symlinks: none")
    else Vector("   symlinks:") ++ tool.symlinks.map(renderSymlinkCommand(tool, _))

  private def renderSymlinkCommand(tool: ResolvedTool, symlink: ResolvedSymlink): String =
    val destination = absoluteOrInstallPath(tool.installDir, symlink.path)
    val command     = symlink.privilege match
      case SymlinkPrivilege.User =>
        s"ln -sfn ${shellQuote(symlink.target)} ${shellQuote(destination)}"
      case SymlinkPrivilege.Sudo =>
        s"sudo ln -sfn ${shellQuote(symlink.target)} ${shellQuote(destination)}"
    val risk = symlink.privilege match
      case SymlinkPrivilege.User => "local"
      case SymlinkPrivilege.Sudo => "sudo risk"
    s"     [$risk] $command"

  private def absoluteOrInstallPath(installDir: String, path: String): String =
    if path.startsWith("/") then path else joinPath(installDir, path)

  private def joinPath(parent: String, child: String): String =
    if parent.endsWith("/") then s"$parent$child" else s"$parent/$child"

  private def shellQuote(value: String): String = s"'${value.replace("'", "'\"'\"'")}'"

private final case class ResolvedValue[+A](value: A, errors: Vector[ValidationError]):
  def map[B](f: A => B): ResolvedValue[B] = ResolvedValue(f(value), errors)

private object ResolvedValue:
  def valid[A](value: A): ResolvedValue[A] = ResolvedValue(value, Vector.empty)

  def invalid[A](value: A, path: String, message: String): ResolvedValue[A] =
    ResolvedValue(value, Vector(ValidationError(path, message)))

private final class ResolutionBuilder(
    profile: BinaryDistributionProfile,
    options: ResolutionOptions,
    httpTextClient: HttpTextClient
):

  def resolve(): ResolvedValue[ResolvedPlan] =
    val manifestVars = resolveManifestVars()
    val policy       = resolvePolicy(options.runtimeVariables ++ manifestVars.value)
    val baseVars     = options.runtimeVariables ++ manifestVars.value +
      ("appsDir" -> policy.value.appsDir)
    val versions = resolveVersions(baseVars)
    val tools    = resolveTools(baseVars, versions.value)

    ResolvedValue(
      ResolvedPlan(policy.value, tools.value),
      manifestVars.errors ++ policy.errors ++ versions.errors ++ tools.errors
    )

  private def resolveManifestVars(): ResolvedValue[Map[String, String]] =
    val rawVars  = profile.spec.vars
    val resolved = rawVars.toVector.map:
      case (name, value) =>
        val path = s"spec.vars.$name"
        name -> interpolate(value, path, options.runtimeVariables ++ rawVars)
    ResolvedValue(
      resolved.map((name, value) => name -> value.value).toMap,
      resolved.flatMap((_, value) => value.errors)
    )

  private def resolvePolicy(vars: Map[String, String]): ResolvedValue[ResolvedPolicy] =
    val appsDir   = interpolate(profile.spec.policy.appsDir, "spec.policy.appsDir", vars)
    val stateFile = profile.spec.policy.stateFile match
      case Some(value) => interpolate(value, "spec.policy.stateFile", vars).map(Some(_))
      case None        => ResolvedValue.valid(None)

    ResolvedValue(
      ResolvedPolicy(appsDir.value, stateFile.value),
      appsDir.errors ++ stateFile.errors
    )

  private def resolveVersions(
      vars: Map[String, String]
  ): ResolvedValue[Map[String, ResolvedVersion]] =
    val resolved = profile.spec.versions.toVector.map:
      case (name, source) => name -> resolveVersionSource(name, source, vars)
    ResolvedValue(
      resolved.map((name, value) => name -> value.value).toMap,
      resolved.flatMap((_, value) => value.errors)
    )

  private def resolveVersionSource(
      name: String,
      source: VersionSource,
      vars: Map[String, String]
  ): ResolvedValue[ResolvedVersion] = source match
    case VersionSource.Pinned(value) =>
      val path     = s"spec.versions.$name"
      val resolved = interpolate(value, path, vars)
      val errors   = resolved.errors ++ missingConcreteVersionErrors(resolved.value, path, name)
      ResolvedValue(ResolvedVersion.Concrete(resolved.value), errors)
    case VersionSource.Dynamic(DynamicVersionKind.LatestUrl, note) =>
      ResolvedValue.valid(ResolvedVersion.DynamicLatestUrl(note))
    case VersionSource.Resolver(VersionResolverKind.HttpText, url) =>
      resolveHttpTextVersion(name, url, vars)

  private def resolveHttpTextVersion(
      name: String,
      url: String,
      vars: Map[String, String]
  ): ResolvedValue[ResolvedVersion] =
    val path        = s"spec.versions.$name.resolver.url"
    val resolvedUrl = interpolate(url, path, vars)
    val fetched     =
      if resolvedUrl.errors.nonEmpty then ResolvedValue.valid("")
      else
        httpTextClient.getText(resolvedUrl.value) match
          case Right(text) => ResolvedValue.valid(text.trim)
          case Left(error) =>
            ResolvedValue.invalid("", path, s"http-text resolver failed: ${error.message}")

    ResolvedValue(
      ResolvedVersion.Concrete(fetched.value),
      resolvedUrl.errors ++ fetched.errors ++
        missingConcreteVersionErrors(fetched.value, path, name)
    )

  private def missingConcreteVersionErrors(
      value: String,
      path: String,
      name: String
  ): Vector[ValidationError] =
    if value.nonEmpty then Vector.empty
    else Vector(ValidationError(path, s"version '$name' did not resolve to a concrete value"))

  private def resolveTools(
      baseVars: Map[String, String],
      versions: Map[String, ResolvedVersion]
  ): ResolvedValue[Vector[ResolvedTool]] =
    val resolved = profile.spec.plan.zipWithIndex.map:
      case (entry, index) => resolveTool(entry, index, baseVars, versions)
    ResolvedValue(
      resolved.map(_.value),
      resolved.flatMap(_.errors)
    )

  private def resolveTool(
      entry: PlanEntry,
      index: Int,
      baseVars: Map[String, String],
      versions: Map[String, ResolvedVersion]
  ): ResolvedValue[ResolvedTool] =
    val spec        = entry.spec
    val version     = versions.getOrElse(spec.versionRef, ResolvedVersion.Concrete(""))
    val versionVars = concreteVersionVars(version)
    val vars        = baseVars ++ versionVars + ("tool" -> entry.name)
    val specPath    = s"spec.plan[$index].spec"
    val installDir  = interpolate(spec.installDir, s"$specPath.installDir", vars)
    val filename    = interpolate(spec.download.filename, s"$specPath.download.filename", vars)
    val localVars   = vars ++ Map(
      "installDir"   -> installDir.value,
      "downloadPath" -> joinPath(installDir.value, filename.value)
    )
    val download          = resolveDownload(spec.download, specPath, localVars, version)
    val createDirectories = resolveStringVector(
      spec.createDirectories,
      s"$specPath.createDirectories",
      localVars,
      version
    )
    val installer   = resolveInstaller(spec.installer, specPath, localVars, version)
    val executables = resolveExecutables(spec, specPath, localVars, version)
    val symlinks    = resolveSymlinks(spec, specPath, localVars, version)

    ResolvedValue(
      ResolvedTool(
        name = entry.name,
        description = entry.description,
        version = version,
        installDir = installDir.value,
        createDirectories = createDirectories.value,
        download = download.value,
        installer = installer.value,
        executables = executables.value,
        symlinks = symlinks.value
      ),
      installDir.errors ++
        versionTemplateErrors(spec.installDir, s"$specPath.installDir", version) ++
        filename.errors ++
        versionTemplateErrors(spec.download.filename, s"$specPath.download.filename", version) ++
        createDirectories.errors ++ download.errors ++ installer.errors ++ executables.errors ++
        symlinks.errors
    )

  private def concreteVersionVars(version: ResolvedVersion): Map[String, String] = version match
    case ResolvedVersion.Concrete(value) if value.nonEmpty => Map("version" -> value)
    case _                                                 => Map.empty

  private def resolveDownload(
      download: DownloadSpec,
      specPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[ResolvedDownload] =
    val path     = s"$specPath.download"
    val url      = interpolate(download.url, s"$path.url", vars)
    val filename = interpolate(download.filename, s"$path.filename", vars)
    val archive  = resolveArchive(download.archive, path, vars, version)

    ResolvedValue(
      ResolvedDownload(url.value, filename.value, download.checksum, archive.value),
      url.errors ++ versionTemplateErrors(download.url, s"$path.url", version) ++
        filename.errors ++ versionTemplateErrors(download.filename, s"$path.filename", version) ++
        archive.errors
    )

  private def resolveArchive(
      archive: Option[ArchiveSpec],
      downloadPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Option[ResolvedArchive]] = archive match
    case None        => ResolvedValue.valid(None)
    case Some(value) =>
      val files = resolveExtractMappings(
        value.extract.files,
        s"$downloadPath.archive.extract.files",
        vars,
        version
      )
      val directories = resolveExtractMappings(
        value.extract.directories,
        s"$downloadPath.archive.extract.directories",
        vars,
        version
      )
      ResolvedValue(
        Some(ResolvedArchive(value, files.value, directories.value)),
        files.errors ++ directories.errors
      )

  private def resolveExtractMappings(
      mappings: Vector[ExtractMapping],
      path: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Vector[ResolvedExtractMapping]] =
    val resolved = mappings.zipWithIndex.map:
      case (mapping, index) =>
        val fromPath = s"$path[$index].from"
        val toPath   = s"$path[$index].to"
        val from     = interpolate(mapping.from, fromPath, vars)
        val to       = interpolate(mapping.to, toPath, vars)
        ResolvedValue(
          ResolvedExtractMapping(from.value, to.value),
          from.errors ++ versionTemplateErrors(mapping.from, fromPath, version) ++
            to.errors ++ versionTemplateErrors(mapping.to, toPath, version)
        )
    ResolvedValue(resolved.map(_.value), resolved.flatMap(_.errors))

  private def resolveInstaller(
      installer: Option[InstallerSpec],
      specPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Option[ResolvedInstaller]] = installer match
    case None        => ResolvedValue.valid(None)
    case Some(value) =>
      val path = s"$specPath.installer"
      val args = resolveStringVector(value.args, s"$path.args", vars, version)
      val env  = resolveInstallerEnv(value.env, path, vars, version)
      ResolvedValue(
        Some(ResolvedInstaller(value.shell, args.value, env.value, value.cleanup)),
        args.errors ++ env.errors
      )

  private def resolveInstallerEnv(
      env: Vector[InstallerEnv],
      installerPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Vector[ResolvedInstallerEnv]] =
    val resolved = env.zipWithIndex.map:
      case (entry, index) =>
        val path  = s"$installerPath.env[$index].value"
        val value = interpolate(entry.value, path, vars)
        ResolvedValue(
          ResolvedInstallerEnv(entry.name, value.value),
          value.errors ++ versionTemplateErrors(entry.value, path, version)
        )
    ResolvedValue(resolved.map(_.value), resolved.flatMap(_.errors))

  private def resolveExecutables(
      spec: BinaryToolSpec,
      specPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Vector[ResolvedExecutable]] =
    val resolved = spec.executables.zipWithIndex.map:
      case (executable, index) =>
        val path  = s"$specPath.executables[$index].path"
        val value = interpolate(executable.path, path, vars)
        ResolvedValue(
          ResolvedExecutable(value.value, executable.mode),
          value.errors ++ versionTemplateErrors(executable.path, path, version)
        )
    ResolvedValue(resolved.map(_.value), resolved.flatMap(_.errors))

  private def resolveSymlinks(
      spec: BinaryToolSpec,
      specPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Vector[ResolvedSymlink]] =
    val resolved = spec.symlinks.zipWithIndex.map:
      case (symlink, index) =>
        val pathPath   = s"$specPath.symlinks[$index].path"
        val targetPath = s"$specPath.symlinks[$index].target"
        val path       = interpolate(symlink.path, pathPath, vars)
        val target     = interpolate(symlink.target, targetPath, vars)
        ResolvedValue(
          ResolvedSymlink(path.value, target.value, symlink.privilege),
          path.errors ++ versionTemplateErrors(symlink.path, pathPath, version) ++
            target.errors ++ versionTemplateErrors(symlink.target, targetPath, version)
        )
    ResolvedValue(resolved.map(_.value), resolved.flatMap(_.errors))

  private def resolveStringVector(
      values: Vector[String],
      path: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Vector[String]] =
    val resolved = values.zipWithIndex.map:
      case (value, index) =>
        val itemPath = s"$path[$index]"
        val item     = interpolate(value, itemPath, vars)
        ResolvedValue(item.value, item.errors ++ versionTemplateErrors(value, itemPath, version))
    ResolvedValue(resolved.map(_.value), resolved.flatMap(_.errors))

  private def versionTemplateErrors(
      value: String,
      path: String,
      version: ResolvedVersion
  ): Vector[ValidationError] = version match
    case ResolvedVersion.Concrete(value) if value.nonEmpty => Vector.empty
    case _ => TemplateInterpolator.variableNames(value).collect:
        case "version" => ValidationError(
            path,
            "template references ${version}, but no concrete version is available"
          )

  private def interpolate(
      value: String,
      path: String,
      vars: Map[String, String]
  ): ResolvedValue[String] = TemplateInterpolator.interpolate(value, path, vars)

  private def joinPath(parent: String, child: String): String =
    if parent.endsWith("/") then s"$parent$child" else s"$parent/$child"

private object TemplateInterpolator:
  private val Variable: Regex = "\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}".r

  def interpolate(
      value: String,
      path: String,
      vars: Map[String, String]
  ): ResolvedValue[String] =
    val errors = variableNames(value).distinct.flatMap: name =>
      if vars.contains(name) then Vector.empty
      else Vector(ValidationError(path, s"unresolved variable '$name'"))

    val rendered = Variable.replaceAllIn(
      value,
      matched => Regex.quoteReplacement(vars.getOrElse(matched.group(1), matched.matched))
    )
    ResolvedValue(rendered, errors)

  def variableNames(value: String): Vector[String] =
    Variable.findAllMatchIn(value).map(_.group(1)).toVector

private final class JdkHttpTextClient(client: HttpClient) extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] =
    val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
    Try(client.send(request, HttpResponse.BodyHandlers.ofString())) match
      case Success(response) if response.statusCode() >= 200 && response.statusCode() < 300 =>
        Right(response.body())
      case Success(response) => Left(HttpTextError(url, s"HTTP ${response.statusCode()}"))
      case Failure(error)    => Left(HttpTextError(url, error.getMessage))
