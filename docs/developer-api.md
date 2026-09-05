# Developer API

`BinaryInstaller` is the typed, embedded entry point for resolving plans and writing lock files.
The existing `BinaryInstallerService` and `InstallerOptions` APIs remain available for CLI-shaped
integrations and rendered output.

Use `BinaryInstaller.useDefault` for production code. It creates one JDK HTTP client for the
synchronous callback and closes it after the callback returns or throws. Do not retain the
installer outside that callback. Advanced integrations can use `BinaryInstallerService.resolving`
to inject HTTP, metadata, persistence, and installation boundaries. Related API references are the
JDK client's [deterministic lifecycle](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)
and Coursier's [embedded API](https://get-coursier.io/docs/api).

```scala
import binstaller.core.*

BinaryInstaller.useDefault: installer =>
  installer.plan(PlanRequest(ProfileInput.Yaml("""
    |apiVersion: binstaller.io/v1alpha1
    |kind: BinaryDistributionProfile
    |metadata:
    |  name: offline-example
    |spec:
    |  policy:
    |    appsDir: /opt/offline-tools
    |  vars: {}
    |  versions:
    |    hello: "1.0.0"
    |  plan:
    |    - name: hello
    |      kind: binary-tool
    |      spec:
    |        versionRef: hello
    |        installDir: /opt/offline-tools/hello
    |        download:
    |          url: https://example.invalid/hello-${version}
    |          filename: hello
    |          checksum:
    |            algorithm: sha256
    |            value: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    |        executables:
    |          - path: bin/hello
    |  """.stripMargin))) match
    case Right(plan) =>
      plan.tools.foreach(tool => println(s"${tool.name}: ${tool.version}"))
    case Left(error) => Console.err.println(ResolvePlanError.renderLines(error).mkString("\n"))
```

This example does not contact a version resolver or checksum-discovery source: the version and
SHA-256 digest are literals. `plan` is therefore offline. A later `apply` or `lock` still needs
the artifact URL when it downloads or inspects that artifact.

To create a lock file, pass an explicit `Path`; success returns both the normalized path that was
saved and the exact `LockFile` value that was stored.

```scala
import binstaller.core.*
import java.nio.file.Path

BinaryInstaller.useDefault: installer =>
  installer.lock(
    LockRequest(ProfileInput.File(Path.of("profile.yaml")), Path.of("binstaller.lock.json"))
  ) match
    case Right(report) => println(report.path)
    case Left(error)   => Console.err.println(LockCommandError.renderLines(error).mkString("\n"))
```

`ProfileInput.File` reads YAML from a path and `ProfileInput.Yaml` accepts in-memory YAML. The
new request types are additive. See the [prerelease config API](architecture.md#prerelease-config-api)
for the validated configuration model and compatibility notes.
