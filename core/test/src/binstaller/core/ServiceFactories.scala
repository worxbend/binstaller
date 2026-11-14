package binstaller.core

import binstaller.config.ConfigModule
import binstaller.config.Sha256Digest

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import scala.jdk.CollectionConverters.*
import scala.util.Using
import upickle.default.write

/** Service, lock-file, and state-file factories shared by the apply and lock test suites. */
private[core] trait ServiceFactories extends CoreTestPrimitives:

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
          name = toolName("alpha"),
          resolvedVersion = Some("1.0.0"),
          versionProvenance = None,
          downloadProvenance = UrlProvenance.direct("https://example.invalid/alpha-1.0.0"),
          sizeBytes = Some(11L),
          checksum = Some(LockFileChecksum("sha256", "a" * 64)),
          dynamicSource = false
        ),
        LockFileTool(
          name = toolName("beta"),
          resolvedVersion = Some("2.0.0"),
          versionProvenance = Some(betaVersionProvenance),
          downloadProvenance = betaDownloadProvenance,
          sizeBytes = Some(22L),
          checksum = Some(LockFileChecksum.inspected("sha256", "b" * 64)),
          dynamicSource = false
        ),
        LockFileTool(
          name = toolName("gamma"),
          resolvedVersion = None,
          versionProvenance = None,
          downloadProvenance = UrlProvenance.direct("https://example.invalid/latest/gamma"),
          sizeBytes = dynamicSize,
          checksum = dynamicSize.map(_ =>
            LockFileChecksum.inspected("sha256", "c" * 64)
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

  /** The beta download as a CDN would report it: same route, per-request signed query. */
  protected def signedBetaDownloadProvenance(signature: String): UrlProvenance =
    val signedTarget = s"${betaDownloadProvenance.finalUrl}?se=1&sig=$signature"
    UrlProvenance(
      betaDownloadProvenance.initialUrl,
      signedTarget,
      Vector(UrlRedirectHop(betaDownloadProvenance.initialUrl, signedTarget, 301))
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

  protected def hasStagedInstall(tempRoot: Path, installName: String): Boolean =
    val prefix = s".$installName.stage-"
    var found  = false
    // Callers poll while an install runs and deletes files under tempRoot, and Files.walk throws
    // on an entry that disappears between listing and visiting it. Such an entry is skipped.
    val _ = Files.walkFileTree(
      tempRoot,
      new SimpleFileVisitor[Path]:
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
          visit(dir)
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
          visit(file)
        override def visitFileFailed(file: Path, error: IOException): FileVisitResult = error match
          case _: NoSuchFileException => FileVisitResult.CONTINUE
          case other                  => throw other
        private def visit(path: Path): FileVisitResult =
          if path.getFileName.toString.startsWith(prefix) then
            found = true
            FileVisitResult.TERMINATE
          else FileVisitResult.CONTINUE
    )
    found
