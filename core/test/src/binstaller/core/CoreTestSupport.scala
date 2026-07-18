package binstaller.core

import binstaller.config.ConfigModule
import binstaller.config.ArchiveExtract
import binstaller.config.ArchiveSpec
import binstaller.config.ArchiveType
import binstaller.config.ExtractMapping
import binstaller.config.ValidationError
import binstaller.config.SymlinkPrivilege
import utest.*

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import scala.jdk.CollectionConverters.*
import scala.util.Using
import upickle.default.write

private[core] trait CoreTestSupport:

  protected def resolve(
      yaml: String,
      httpTextClient: HttpTextClient = FakeHttpTextClient("")
  ): ResolvedPlan =
    val profile = ConfigModule.loadString(yaml) match
      case Right(value) => value
      case Left(error)  => abort(s"expected valid config, got $error")

    PlanResolver.resolve(profile, testResolutionOptions, httpTextClient) match
      case Right(plan)                                     => plan
      case Left(ResolvePlanError.ValidationFailed(errors)) =>
        abort(s"expected resolved plan, got ${errors.mkString(", ")}")

  protected def resolveErrors(yaml: String): Vector[ValidationError] =
    val profile = ConfigModule.loadString(yaml) match
      case Right(value) => value
      case Left(error)  => abort(s"expected config decode success, got $error")

    PlanResolver.resolve(profile, testResolutionOptions, FakeHttpTextClient("")) match
      case Left(ResolvePlanError.ValidationFailed(errors)) => errors
      case Right(plan) => abort(s"expected resolution errors, got $plan")

  protected def resolveExampleConfig(httpTextClient: HttpTextClient): ResolvedPlan =
    val profile = ConfigModule.load(exampleConfigPath) match
      case Right(value) => value
      case Left(error)  => abort(s"expected valid example config, got $error")

    PlanResolver.resolve(profile, testResolutionOptions, httpTextClient) match
      case Right(plan)                                     => plan
      case Left(ResolvePlanError.ValidationFailed(errors)) =>
        abort(s"expected resolved example config, got ${errors.mkString(", ")}")

  protected def onlyTool(plan: ResolvedPlan): ResolvedTool = plan.tools match
    case Vector(tool) => tool
    case other        => abort(s"expected one tool, got ${other.size}")

  protected def assertInstallSuccess(
      result: Either[ToolInstallError, ToolInstallSuccess],
      installDir: String
  ): Unit = result match
    case Right(success) =>
      assert(success.toolName == "alpha")
      assert(success.installDir == installDir)
    case Left(error) => abort(s"expected install success, got $error")

  protected def errorAt(path: String)(error: ValidationError): Boolean = error.path == path

  protected def versionSummaryRowExists(
      lines: Vector[String],
      packageName: String,
      version: String,
      newerVersion: String
  ): Boolean = lines.exists(line =>
    line.startsWith(packageName) &&
      line.contains(version) &&
      line.endsWith(newerVersion)
  )

  protected def eventIndex(
      events: Vector[InstallerEvent],
      matches: PartialFunction[InstallerEvent, Boolean]
  ): Int =
    val index = events.indexWhere(event => matches.applyOrElse(event, (_: InstallerEvent) => false))
    if index >= 0 then index
    else abort(s"event not found in ${events.mkString(", ")}")

  protected def abort(message: String): Nothing = throw java.lang.AssertionError(message)

  protected def statefulService(
      cwd: Path,
      downloadClient: BinaryDownloadClient
  ): BinaryInstallerService = BinaryInstallerService.resolving(
    FakeHttpTextClient(""),
    DirectBinaryInstaller(downloadClient, InstallFileSystem.nio),
    ApplyStateStore.nio(cwd)
  )

  protected def lockedApplyService(
      tempRoot: Path,
      dynamicSize: Option[Long],
      installer: DirectBinaryInstaller = DirectBinaryInstaller(
        RoutingBinaryDownloadClient.success,
        InstallFileSystem.nio
      )
  ): BinaryInstallerService = BinaryInstallerService.resolving(
    LockHttpTextClient("2.0.0", betaVersionProvenance),
    installer,
    ApplyStateStore.nio(tempRoot),
    lockMetadataClient(dynamicSize),
    LockFileStore.nio
  )

  protected def applyOptions(config: Path): InstallerOptions = InstallerOptions(
    configPath = config.toString,
    statePath = None,
    resetState = ResetState.Disabled,
    verboseOutput = VerboseOutput.Disabled
  )

  protected def writeLock(path: Path, lockFile: LockFile): Unit =
    val _ = Files.writeString(path, write(lockFile, indent = 2))

  protected def currentLockFile(config: Path, dynamicSize: Option[Long]): LockFile =
    val profile = ConfigModule.load(config.toString) match
      case Right(value) => value
      case Left(error)  => abort(s"expected valid config, got $error")
    LockFile(
      LockFile.schemaVersion,
      profile.metadata.name,
      ManifestFingerprint.profile(profile),
      Vector(
        LockFileTool(
          name = "alpha",
          resolvedVersion = Some("1.0.0"),
          versionProvenance = None,
          downloadProvenance = UrlProvenance.direct("https://example.invalid/alpha-1.0.0"),
          sizeBytes = Some(11L),
          checksum = Some(LockFileChecksum("sha256", "a" * 64)),
          dynamicSource = false
        ),
        LockFileTool(
          name = "beta",
          resolvedVersion = Some("2.0.0"),
          versionProvenance = Some(betaVersionProvenance),
          downloadProvenance = betaDownloadProvenance,
          sizeBytes = Some(22L),
          checksum = Some(LockFileChecksum(
            "sha256",
            "b" * 64,
            "inspected",
            None,
            None,
            None
          )),
          dynamicSource = false
        ),
        LockFileTool(
          name = "gamma",
          resolvedVersion = None,
          versionProvenance = None,
          downloadProvenance = UrlProvenance.direct("https://example.invalid/latest/gamma"),
          sizeBytes = dynamicSize,
          checksum = dynamicSize.map(_ =>
            LockFileChecksum(
              "sha256",
              "c" * 64,
              "inspected",
              None,
              None,
              None
            )
          ),
          dynamicSource = true
        )
      )
    )

  protected def lockMetadataClient(dynamicSize: Option[Long]): BinaryMetadataClient =
    RoutingBinaryMetadataClient(Map(
      "https://example.invalid/alpha-1.0.0" -> BinaryMetadata(
        Some(11L),
        UrlProvenance.direct("https://example.invalid/alpha-1.0.0"),
        Some(Sha256Digest.trusted("a" * 64))
      ),
      "https://example.invalid/beta-2.0.0" -> BinaryMetadata(
        Some(22L),
        betaDownloadProvenance,
        Some(Sha256Digest.trusted("b" * 64))
      ),
      "https://example.invalid/latest/gamma" -> BinaryMetadata(
        dynamicSize,
        UrlProvenance.direct("https://example.invalid/latest/gamma"),
        Some(Sha256Digest.trusted("c" * 64))
      )
    ))

  protected val betaVersionProvenance: UrlProvenance = UrlProvenance(
    "https://example.invalid/beta-version",
    "https://cdn.example.invalid/beta-version",
    Vector(UrlRedirectHop(
      "https://example.invalid/beta-version",
      "https://cdn.example.invalid/beta-version",
      302
    ))
  )

  protected val betaDownloadProvenance: UrlProvenance = UrlProvenance(
    "https://example.invalid/beta-2.0.0",
    "https://cdn.example.invalid/beta-2.0.0",
    Vector(UrlRedirectHop(
      "https://example.invalid/beta-2.0.0",
      "https://cdn.example.invalid/beta-2.0.0",
      301
    ))
  )

  protected def writeConfig(tempRoot: Path, content: String): Path =
    val config = tempRoot.resolve("profile.yaml")
    Files.writeString(config, content)
    config

  protected def loadState(tempRoot: Path, name: String): ApplyState =
    ApplyStateStore.nio(tempRoot).load(tempRoot.resolve(name)) match
      case Right(Some(state)) => state
      case other              => abort(s"expected saved state, got $other")

  protected def hasTempStateFile(
      tempRoot: Path,
      name: String
  ): Boolean = Using.resource(Files.list(tempRoot)): stream =>
    stream
      .iterator()
      .asScala
      .exists(path => path.getFileName.toString.startsWith(s".$name.tmp-"))

  protected def hasStagedInstall(
      tempRoot: Path,
      installName: String
  ): Boolean = Using.resource(Files.walk(tempRoot)): stream =>
    stream
      .iterator()
      .asScala
      .exists(path => path.getFileName.toString.startsWith(s".$installName.stage-"))

  protected def sha256(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(byte => f"${byte & 0xff}%02x").mkString

  protected def exampleConfigPath: Path = repoRootCandidates
    .map(_.resolve("config.example.yaml"))
    .find(Files.exists(_))
    .getOrElse(abort("could not locate config.example.yaml"))

  protected def repoRootCandidates: Iterator[Path] =
    sys.props.get("binstaller.repoRoot").iterator.map(Path.of(_).toAbsolutePath) ++
      upwardPaths(Path.of("").toAbsolutePath)

  protected def upwardPaths(start: Path): Iterator[Path] =
    Iterator.iterate(start)(_.getParent).takeWhile(_ != null)

  protected def directTool(
      installDir: Path,
      checksum: Option[ResolvedChecksum] = None,
      executables: Vector[ResolvedExecutable] = Vector(ResolvedExecutable("bin/alpha", None)),
      symlinks: Vector[ResolvedSymlink] = Vector.empty
  ): ResolvedTool = ResolvedTool(
    name = "alpha",
    description = None,
    version = ResolvedVersion.Concrete("1.0.0"),
    installDir = installDir.toString,
    createDirectories = Vector("bin"),
    download = ResolvedDownload(
      url = "https://example.invalid/alpha",
      filename = "alpha",
      checksum = checksum,
      archive = None
    ),
    executables = executables,
    symlinks = symlinks
  )

  protected def sudoSymlinkTool(installDir: Path): ResolvedTool = directTool(
    installDir,
    symlinks = Vector(
      ResolvedSymlink("/usr/local/bin/alpha", "bin/alpha", SymlinkPrivilege.Sudo)
    )
  )

  protected def sudoSymlinkYaml(
      tempRoot: Path,
      installDir: Path,
      stateFile: String
  ): String = s"""
                 |apiVersion: binstaller.io/v1alpha1
                 |kind: BinaryDistributionProfile
                 |metadata:
                 |  name: sudo-redaction
                 |spec:
                 |  policy:
                 |    appsDir: "$tempRoot"
                 |    stateFile: "$stateFile"
                 |    allowSudoSymlinks: true
                 |  versions:
                 |    alpha: "1.0.0"
                 |  plan:
                 |    - name: alpha
                 |      kind: binary-tool
                 |      spec:
                 |        versionRef: alpha
                 |        installDir: "$installDir"
                 |        createDirectories:
                 |          - bin
                 |        download:
                 |          url: "https://example.invalid/alpha"
                 |          filename: alpha
                 |        executables:
                 |          - path: bin/alpha
                 |        symlinks:
                 |          - path: /usr/local/bin/alpha
                 |            target: "$installDir/bin/alpha"
                 |            sudo: true
                 |""".stripMargin

  protected def archiveTool(
      installDir: Path,
      archiveType: ArchiveType,
      files: Vector[(String, String)] = Vector.empty,
      directories: Vector[(String, String)] = Vector.empty,
      executable: String = "bin/alpha"
  ): ResolvedTool = ResolvedTool(
    name = "alpha",
    description = None,
    version = ResolvedVersion.Concrete("1.0.0"),
    installDir = installDir.toString,
    createDirectories = Vector.empty,
    download = ResolvedDownload(
      url = "https://example.invalid/alpha-archive",
      filename = "alpha-archive",
      checksum = None,
      archive = Some(
        ResolvedArchive(
          ArchiveSpec(
            archiveType,
            ArchiveExtract(
              files.map((from, to) => ExtractMapping(from, to)),
              directories.map((from, to) => ExtractMapping(from, to))
            )
          ),
          files.map((from, to) => ResolvedExtractMapping(from, to)),
          directories.map((from, to) => ResolvedExtractMapping(from, to))
        )
      )
    ),
    executables = Vector(ResolvedExecutable(executable, None)),
    symlinks = Vector.empty
  )

  protected def zipArchive(entries: Vector[(String, String)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    Using.resource(ZipOutputStream(output)): zip =>
      entries.foreach:
        case (name, content) =>
          zip.putNextEntry(ZipEntry(name))
          zip.write(content.getBytes(StandardCharsets.UTF_8))
          zip.closeEntry()
    output.toByteArray

  protected def zipArchiveWithDuplicateLocalEntries(entries: Vector[(String, String)])
      : Array[Byte] =
    val output = ByteArrayOutputStream()
    entries.foreach:
      case (name, content) =>
        val nameBytes    = name.getBytes(StandardCharsets.UTF_8)
        val contentBytes = content.getBytes(StandardCharsets.UTF_8)
        val crc          = CRC32()
        crc.update(contentBytes)
        writeLittleInt(output, 0x04034b50)
        writeLittleShort(output, 20)
        writeLittleShort(output, 0)
        writeLittleShort(output, 0)
        writeLittleShort(output, 0)
        writeLittleShort(output, 0)
        writeLittleInt(output, crc.getValue.toInt)
        writeLittleInt(output, contentBytes.length)
        writeLittleInt(output, contentBytes.length)
        writeLittleShort(output, nameBytes.length)
        writeLittleShort(output, 0)
        output.write(nameBytes)
        output.write(contentBytes)
    output.toByteArray

  protected def writeLittleShort(output: ByteArrayOutputStream, value: Int): Unit =
    output.write(value & 0xff)
    output.write((value >>> 8) & 0xff)

  protected def writeLittleInt(output: ByteArrayOutputStream, value: Int): Unit =
    writeLittleShort(output, value & 0xffff)
    writeLittleShort(output, (value >>> 16) & 0xffff)

  protected def tarGzArchive(entries: Vector[(String, String)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    val gzip   = GZIPOutputStream(output)
    entries.foreach:
      case (name, content) =>
        val bytes = content.getBytes(StandardCharsets.UTF_8)
        gzip.write(tarHeader(name, bytes.length, '0'))
        gzip.write(bytes)
        val padding = (512 - (bytes.length % 512)) % 512
        gzip.write(Array.fill[Byte](padding)(0))
    gzip.write(Array.fill[Byte](1024)(0))
    gzip.close()
    output.toByteArray

  protected def tarXzArchive(entries: Vector[(String, String)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    val xz     = XZOutputStream(output, LZMA2Options())
    entries.foreach:
      case (name, content) =>
        val bytes = content.getBytes(StandardCharsets.UTF_8)
        xz.write(tarHeader(name, bytes.length, '0'))
        xz.write(bytes)
        val padding = (512 - (bytes.length % 512)) % 512
        xz.write(Array.fill[Byte](padding)(0))
    xz.write(Array.fill[Byte](1024)(0))
    xz.close()
    output.toByteArray

  protected def tarGzArchiveWithDirectories(
      directories: Vector[String],
      files: Vector[(String, String)]
  ): Array[Byte] =
    val output = ByteArrayOutputStream()
    val gzip   = GZIPOutputStream(output)
    directories.foreach: name =>
      gzip.write(tarHeader(name, 0, '5'))
    files.foreach:
      case (name, content) =>
        val bytes = content.getBytes(StandardCharsets.UTF_8)
        gzip.write(tarHeader(name, bytes.length, '0'))
        gzip.write(bytes)
        val padding = (512 - (bytes.length % 512)) % 512
        gzip.write(Array.fill[Byte](padding)(0))
    gzip.write(Array.fill[Byte](1024)(0))
    gzip.close()
    output.toByteArray

  protected def tarGzArchiveWithEntryTypes(entries: Vector[(String, String, Char)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    val gzip   = GZIPOutputStream(output)
    entries.foreach:
      case (name, content, entryType) =>
        val bytes = content.getBytes(StandardCharsets.UTF_8)
        gzip.write(tarHeader(name, bytes.length, entryType))
        if entryType == '0' then
          gzip.write(bytes)
          val padding = (512 - (bytes.length % 512)) % 512
          gzip.write(Array.fill[Byte](padding)(0))
    gzip.write(Array.fill[Byte](1024)(0))
    gzip.close()
    output.toByteArray

  protected def tarHeader(name: String, size: Int, entryType: Char): Array[Byte] =
    val header = Array.fill[Byte](512)(0)
    writeTarField(header, 0, 100, name)
    writeTarField(header, 124, 12, f"$size%011o")
    header(156) = entryType.toByte
    header

  protected def writeTarField(header: Array[Byte], offset: Int, length: Int, value: String): Unit =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    Array.copy(bytes, 0, header, offset, math.min(bytes.length, length))

  protected def directBinaryYaml(installDir: Path): String =
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: direct-apply
       |spec:
       |  policy:
       |    appsDir: "${installDir.getParent}"
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$installDir"
       |        createDirectories:
       |          - bin
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |""".stripMargin

  protected def twoToolYaml(
      tempRoot: Path,
      stateFile: String,
      continueOnError: Boolean = false
  ): String =
    val appsDir = tempRoot.resolve("apps")
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: resume-profile
       |spec:
       |  policy:
       |    appsDir: "$appsDir"
       |    stateFile: "$stateFile"
       |    continueOnError: $continueOnError
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |    beta: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$appsDir/alpha"
       |        createDirectories:
       |          - bin
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |    - name: beta
       |      kind: binary-tool
       |      spec:
       |        versionRef: beta
       |        installDir: "$appsDir/beta"
       |        createDirectories:
       |          - bin
       |        download:
       |          url: https://example.invalid/beta
       |          filename: beta
       |        executables:
       |          - path: bin/beta
       |""".stripMargin

  protected def twoSudoToolYaml(tempRoot: Path): String =
    val appsDir = tempRoot.resolve("apps")
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: sudo-profile
       |spec:
       |  policy:
       |    appsDir: "$appsDir"
       |    allowSudoSymlinks: true
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |    beta: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$appsDir/alpha"
       |        createDirectories:
       |          - bin
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |        symlinks:
       |          - path: ${tempRoot.resolve("alpha-link")}
       |            target: bin/alpha
       |            sudo: true
       |    - name: beta
       |      kind: binary-tool
       |      spec:
       |        versionRef: beta
       |        installDir: "$appsDir/beta"
       |        createDirectories:
       |          - bin
       |        download:
       |          url: https://example.invalid/beta
       |          filename: beta
       |        executables:
       |          - path: bin/beta
       |        symlinks:
       |          - path: ${tempRoot.resolve("beta-link")}
       |            target: bin/beta
       |            sudo: true
       |""".stripMargin

  protected def invalidConfigYaml(tempRoot: Path): String =
    s"""
       |apiVersion: wrong.example/v1
       |kind: WrongKind
       |metadata:
       |  name: invalid
       |spec:
       |  policy:
       |    appsDir: "${tempRoot.resolve("apps")}"
       |    continueOnError: no
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "${tempRoot.resolve("apps/alpha")}"
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |""".stripMargin

  protected val testResolutionOptions: ResolutionOptions = ResolutionOptions(
    Map("HOME" -> "/home/test")
  )

  protected val exampleToolNames: Vector[String] = Vector(
    "yazi",
    "zig",
    "minikube",
    "xplr",
    "kind",
    "zellij",
    "helm",
    "kubectl",
    "kustomize",
    "neovide",
    "neovim",
    "lazygit",
    "jujutsu",
    "dotbot",
    "nerd-font-installer"
  )

  protected val validPinnedYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: pinned
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |    allowSudoSymlinks: true
      |  vars:
      |    linuxArch: x86_64
      |  versions:
      |    alpha: "1.2.3"
      |  plan:
      |    - name: alpha
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${appsDir}/alpha-${version}"
      |        download:
      |          url: "https://example.invalid/alpha-${version}-${linuxArch}.tar.gz"
      |          filename: "alpha-${version}.tar.gz"
      |          archive:
      |            type: tar.gz
      |            extract:
      |              files:
      |                - from: "alpha-${version}/alpha"
      |                  to: bin/alpha
      |        executables:
      |          - path: bin/alpha
      |        symlinks:
      |          - path: bin/a
      |            target: "${installDir}/bin/alpha"
      |""".stripMargin

  protected val hostSelectedYaml: String = """
                                             |apiVersion: binstaller.io/v1alpha1
                                             |kind: BinaryDistributionProfile
                                             |metadata:
                                             |  name: selected
                                             |spec:
                                             |  policy:
                                             |    appsDir: "${HOME}/.apps"
                                             |  versions:
                                             |    linux:
                                             |      resolver:
                                             |        type: http-text
                                             |        url: https://example.invalid/linux-version
                                             |    darwin:
                                             |      resolver:
                                             |        type: http-text
                                             |        url: https://example.invalid/darwin-version
                                             |  plan:
                                             |    - name: linux-tool
                                             |      kind: binary-tool
                                             |      when:
                                             |        os:
                                             |          family: linux
                                             |        architecture: x86_64
                                             |      spec:
                                             |        versionRef: linux
                                             |        installDir: "${HOME}/.apps/linux-tool"
                                             |        download:
                                             |          url: https://example.invalid/linux-tool
                                             |          filename: linux-tool
                                             |        executables:
                                             |          - path: bin/linux-tool
                                             |    - name: darwin-tool
                                             |      kind: binary-tool
                                             |      when:
                                             |        os:
                                             |          family: darwin
                                             |      spec:
                                             |        versionRef: darwin
                                             |        installDir: "${HOME}/.apps/darwin-tool"
                                             |        download:
                                             |          url: https://example.invalid/darwin-tool
                                             |          filename: darwin-tool
                                             |        executables:
                                             |          - path: bin/darwin-tool
                                             |""".stripMargin

  protected def lockYaml(tempRoot: Path): String =
    val appsDir = tempRoot.resolve("apps")
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: lock-profile
       |spec:
       |  policy:
       |    appsDir: "$appsDir"
       |    stateFile: lock.state.json
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |    beta:
       |      resolver:
       |        type: http-text
       |        url: https://example.invalid/beta-version
       |    gamma:
       |      dynamic:
       |        type: latest-url
       |        note: latest endpoint
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$appsDir/alpha"
       |        download:
       |          url: https://example.invalid/alpha-$${version}
       |          filename: alpha
       |          checksum:
       |            algorithm: sha256
       |            value: ${"a" * 64}
       |        executables:
       |          - path: bin/alpha
       |    - name: beta
       |      kind: binary-tool
       |      spec:
       |        versionRef: beta
       |        installDir: "$appsDir/beta"
       |        download:
       |          url: https://example.invalid/beta-$${version}
       |          filename: beta
       |        executables:
       |          - path: bin/beta
       |    - name: gamma
       |      kind: binary-tool
       |      spec:
       |        versionRef: gamma
       |        installDir: "$appsDir/gamma"
       |        download:
       |          url: https://example.invalid/latest/gamma
       |          filename: gamma
       |        executables:
       |          - path: bin/gamma
       |""".stripMargin

  protected def githubReleaseYaml(tempRoot: Path, version: String): String =
    val appsDir = tempRoot.resolve("apps")
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: github-latest
       |spec:
       |  policy:
       |    appsDir: "$appsDir"
       |  vars:
       |    muslTarget: x86_64-unknown-linux-musl
       |  versions:
       |    jujutsu: "$version"
       |  plan:
       |    - name: jujutsu
       |      kind: binary-tool
       |      spec:
       |        versionRef: jujutsu
       |        installDir: "$appsDir/jj"
       |        download:
       |          url: "https://github.com/jj-vcs/jj/releases/download/v$version/jj-v$version-$${muslTarget}.tar.gz"
       |          filename: jj.tar.gz
       |          archive:
       |            type: tar.gz
       |            extract:
       |              files:
       |                - from: jj
       |                  to: bin/jj
       |        executables:
       |          - path: bin/jj
       |""".stripMargin

  protected def checksumDiscoveryYaml(tempRoot: Path, checksumFileUrl: String): String =
    val appsDir = tempRoot.resolve("apps")
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: checksum-discovery
       |spec:
       |  policy:
       |    appsDir: "$appsDir"
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$appsDir/alpha"
       |        createDirectories:
       |          - bin
       |        download:
       |          url: https://example.invalid/releases/$${version}/alpha-$${version}.tar.gz
       |          filename: alpha-$${version}.tar.gz
       |          checksum:
       |            algorithm: sha256
       |            discover:
       |              type: sha256sum
       |              url: $checksumFileUrl
       |        executables:
       |          - path: bin/alpha
       |""".stripMargin

  protected val kubectlResolverYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: kubectl
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |  vars: {}
      |  versions:
      |    kubectl:
      |      resolver:
      |        type: http-text
      |        url: https://dl.k8s.io/release/stable.txt
      |  plan:
      |    - name: kubectl
      |      kind: binary-tool
      |      spec:
      |        versionRef: kubectl
      |        installDir: "${appsDir}/kubectl"
      |        download:
      |          url: "https://dl.k8s.io/release/${version}/bin/linux/amd64/kubectl"
      |          filename: kubectl
      |        executables:
      |          - path: bin/kubectl
      |""".stripMargin

  protected val dynamicLatestUrlYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: dynamic
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |  vars: {}
      |  versions:
      |    beta:
      |      dynamic:
      |        type: latest-url
      |        note: upstream latest endpoint
      |  plan:
      |    - name: beta
      |      kind: binary-tool
      |      spec:
      |        versionRef: beta
      |        installDir: "${appsDir}/beta"
      |        download:
      |          url: https://example.invalid/latest/download/beta
      |          filename: beta
      |        executables:
      |          - path: bin/beta
      |""".stripMargin

  protected def strictPolicyYaml(overrides: String = ""): String =
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: strict-policy
       |spec:
       |  policy:
       |    mode: strict
       |    appsDir: "$${HOME}/.apps"
       |    $overrides
       |  vars: {}
       |  versions:
       |    alpha:
       |      dynamic:
       |        type: latest-url
       |        note: upstream latest endpoint
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$${appsDir}/alpha"
       |        download:
       |          url: https://example.invalid/latest/download/alpha.tar.xz
       |          filename: alpha.tar.xz
       |          archive:
       |            type: tar.xz
       |            extract:
       |              files:
       |                - from: alpha
       |                  to: bin/alpha
       |        executables:
       |          - path: bin/alpha
       |""".stripMargin

  protected val invalidVariablesYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: invalid-vars
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |  vars: {}
      |  versions:
      |    alpha: "1.0.0"
      |    beta:
      |      dynamic:
      |        type: latest-url
      |  plan:
      |    - name: alpha
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${appsDir}/${MISSING}"
      |        download:
      |          url: https://example.invalid/alpha
      |          filename: alpha
      |        executables:
      |          - path: bin/alpha
      |    - name: beta
      |      kind: binary-tool
      |      spec:
      |        versionRef: beta
      |        installDir: "${appsDir}/beta"
      |        download:
      |          url: "https://example.invalid/releases/${version}/beta"
      |          filename: beta
      |        executables:
      |          - path: bin/beta
      |""".stripMargin

  protected val shellSyntaxYaml: String = """
                                            |apiVersion: binstaller.io/v1alpha1
                                            |kind: BinaryDistributionProfile
                                            |metadata:
                                            |  name: shell-text
                                            |spec:
                                            |  policy:
                                            |    appsDir: "${HOME}/.apps"
                                            |  vars:
                                            |    shellText: "$(echo should-not-run)"
                                            |  versions:
                                            |    alpha: "1.0.0"
                                            |  plan:
                                            |    - name: alpha
                                            |      kind: binary-tool
                                            |      spec:
                                            |        versionRef: alpha
                                            |        installDir: "${appsDir}/${shellText}"
                                            |        createDirectories:
                                            |          - bin
                                            |        download:
                                            |          url: "https://example.invalid/alpha"
                                            |          filename: alpha
                                            |        executables:
                                            |          - path: bin/alpha
                                            |""".stripMargin

  protected val insecureUrlYaml: String = """
                                            |apiVersion: binstaller.io/v1alpha1
                                            |kind: BinaryDistributionProfile
                                            |metadata:
                                            |  name: insecure
                                            |spec:
                                            |  policy:
                                            |    appsDir: "${HOME}/.apps"
                                            |  vars: {}
                                            |  versions:
                                            |    alpha:
                                            |      resolver:
                                            |        type: http-text
                                            |        url: http://example.invalid/stable.txt
                                            |  plan:
                                            |    - name: alpha
                                            |      kind: binary-tool
                                            |      spec:
                                            |        versionRef: alpha
                                            |        installDir: "${appsDir}/alpha"
                                            |        download:
                                            |          url: http://example.invalid/alpha
                                            |          filename: alpha
                                            |        executables:
                                            |          - path: bin/alpha
                                            |""".stripMargin

  protected val unsafeInstallDirYaml: String = """
                                                 |apiVersion: binstaller.io/v1alpha1
                                                 |kind: BinaryDistributionProfile
                                                 |metadata:
                                                 |  name: unsafe-install-dir
                                                 |spec:
                                                 |  policy:
                                                 |    appsDir: "${HOME}/.apps"
                                                 |  vars: {}
                                                 |  versions:
                                                 |    alpha: "1.0.0"
                                                 |    beta: "1.0.0"
                                                 |    gamma: "1.0.0"
                                                 |  plan:
                                                 |    - name: alpha
                                                 |      kind: binary-tool
                                                 |      spec:
                                                 |        versionRef: alpha
                                                 |        installDir: /tmp/alpha
                                                 |        download:
                                                 |          url: https://example.invalid/alpha
                                                 |          filename: alpha
                                                 |        executables:
                                                 |          - path: bin/alpha
                                                 |    - name: beta
                                                 |      kind: binary-tool
                                                 |      spec:
                                                 |        versionRef: beta
                                                 |        installDir: "${appsDir}/beta"
                                                 |        download:
                                                 |          url: https://example.invalid/beta
                                                 |          filename: beta
                                                 |        executables:
                                                 |          - path: bin/beta
                                                 |    - name: gamma
                                                 |      kind: binary-tool
                                                 |      spec:
                                                 |        versionRef: gamma
                                                 |        installDir: "${appsDir}/beta/nested"
                                                 |        download:
                                                 |          url: https://example.invalid/gamma
                                                 |          filename: gamma
                                                 |        executables:
                                                 |          - path: bin/gamma
                                                 |""".stripMargin

  protected val unsafeInterpolatedPathsYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: unsafe-interpolated-paths
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |    stateFile: "${badTraversal}"
      |    allowSudoSymlinks: true
      |  vars:
      |    badAbsolute: /tmp/binstaller-escape
      |    badTraversal: "../escape"
      |    badControl: "alpha\a"
      |  versions:
      |    alpha: "1.0.0"
      |  plan:
      |    - name: alpha
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${appsDir}/alpha"
      |        createDirectories:
      |          - "bin/${badTraversal}"
      |        download:
      |          url: https://example.invalid/alpha
      |          filename: "${badControl}"
      |          archive:
      |            type: tar.gz
      |            extract:
      |              files:
      |                - from: alpha
      |                  to: "bin/${badTraversal}"
      |        executables:
      |          - path: "${badAbsolute}"
      |        symlinks:
      |          - path: "bin/${badTraversal}"
      |            target: "${badAbsolute}/alpha"
      |    - name: beta
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${appsDir}/${badTraversal}"
      |        download:
      |          url: https://example.invalid/beta
      |          filename: beta
      |        executables:
      |          - path: bin/beta
      |    - name: gamma
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${badAbsolute}/gamma"
      |        download:
      |          url: https://example.invalid/gamma
      |          filename: gamma
      |        executables:
      |          - path: bin/gamma
      |""".stripMargin
