package initkit.config

import utest.*

object InstallerSpecDecoderTests extends TestSuite:

  val tests: Tests = Tests:
    test("decodes valid binary download specs"):
      val spec = decodeValidInstallerSpec(
        "direct-binaries",
        "binary-downloads",
        """
        items:
          - name: helm
            url: https://example.test/helm.tar.gz
            archive:
              type: tar.gz
              stripComponents: 1
              path: linux-amd64/helm
            destination: "${binDir}/helm"
            mode: "0755"
            checksum:
              algorithm: sha256
              value: abc123
        """
      )

      assert(
        spec == InstallerSpec.BinaryDownloads(
          Vector(
            BinaryDownloadItem(
              name = "helm",
              url = "https://example.test/helm.tar.gz",
              destination = "${binDir}/helm",
              mode = "0755",
              checksum = Some(Checksum(ChecksumAlgorithm.Sha256, "abc123")),
              archive = Some(Archive(ArchiveType.TarGz, "linux-amd64/helm", Some(1)))
            )
          )
        )
      )

    test("rejects invalid binary download specs"):
      val errors = validateInstallerSpec(
        "direct-binaries",
        "binary-downloads",
        """
        items:
          - name: kubectl
            url: https://example.test/kubectl
            destination: "${binDir}/kubectl"
        """
      )

      assert(errors.exists(_.message == "spec.plan[0].spec.items[0].mode: is required"))

    test("decodes valid shell script specs"):
      val spec = decodeValidInstallerSpec(
        "language-toolchains",
        "shell-scripts",
        """
        items:
          - name: rustup
            url: https://sh.rustup.rs
            shell: sh
            args:
              - -s
              - --
              - -y
            creates: "${home}/.cargo/bin/rustc"
        """
      )

      assert(
        spec == InstallerSpec.ShellScripts(
          Vector(
            ShellScriptItem(
              name = "rustup",
              url = "https://sh.rustup.rs",
              shell = "sh",
              args = Vector("-s", "--", "-y"),
              creates = Some("${home}/.cargo/bin/rustc")
            )
          )
        )
      )

    test("rejects invalid shell script specs"):
      val errors = validateInstallerSpec(
        "language-toolchains",
        "shell-scripts",
        """
        items:
          - name: rustup
            url: https://sh.rustup.rs
        """
      )

      assert(errors.exists(_.message == "spec.plan[0].spec.items[0].shell: is required"))

    test("decodes valid nerd font specs"):
      val spec = decodeValidInstallerSpec(
        "install-nerd-fonts",
        "nerd-fonts",
        """
        tool:
          path: "${binDir}/nerdfont-install"
          args:
            - -config
            - "${nerdFontConfig}"
        config:
          path: "${nerdFontConfig}"
          create: true
          content:
            release: latest
            families:
              - JetBrainsMono
        preview:
          enabled: true
          args:
            - -dry-run
        """
      )

      spec match
        case InstallerSpec.NerdFonts(tool, config, preview) =>
          assert(tool.path == "${binDir}/nerdfont-install")
          assert(tool.args == Vector("-config", "${nerdFontConfig}"))
          assert(config.path == "${nerdFontConfig}")
          assert(config.create.contains(true))
          assert(config.content.nonEmpty)
          assert(preview.contains(PreviewInvocation(
            enabled = Some(true),
            args = Vector("-dry-run")
          )))
        case other => fail(s"expected nerd fonts spec, found $other")

    test("rejects invalid nerd font specs"):
      val errors = validateInstallerSpec(
        "install-nerd-fonts",
        "nerd-fonts",
        """
        tool:
          path: "${binDir}/nerdfont-install"
        config:
          create: true
        """
      )

      assert(errors.exists(_.message == "spec.plan[0].spec.config.path: is required"))

    test("decodes valid dotfiles apply specs"):
      val spec = decodeValidInstallerSpec(
        "apply-dotfiles",
        "dotfiles-apply",
        """
        tool:
          path: "${binDir}/dotbot-go"
          args:
            - -d
            - "${dotfilesDir}"
        repository:
          url: https://github.com/example/dotfiles.git
          ref: main
          destination: "${dotfilesDir}"
          update: true
        config:
          path: "${dotfilesConfig}"
          sourceUrl: https://example.test/install.conf.yaml
        preview:
          enabled: true
          args:
            - --dry-run
        """
      )

      assert(
        spec == InstallerSpec.DotfilesApply(
          tool = ToolInvocation("${binDir}/dotbot-go", Vector("-d", "${dotfilesDir}")),
          repository = GitRepository(
            url = "https://github.com/example/dotfiles.git",
            ref = Some("main"),
            destination = "${dotfilesDir}",
            update = Some(true)
          ),
          config =
            DotfilesConfig("${dotfilesConfig}", Some("https://example.test/install.conf.yaml")),
          preview = Some(PreviewInvocation(enabled = Some(true), args = Vector("--dry-run")))
        )
      )

    test("rejects invalid dotfiles apply specs"):
      val errors = validateInstallerSpec(
        "apply-dotfiles",
        "dotfiles-apply",
        """
        tool:
          path: "${binDir}/dotbot-go"
        repository:
          url: https://github.com/example/dotfiles.git
        config:
          path: "${dotfilesConfig}"
        """
      )

      assert(errors.exists(_.message == "spec.plan[0].spec.repository.destination: is required"))

    test("decodes valid interrupt specs"):
      val spec = decodeValidInstallerSpec(
        "pause",
        "interrupt",
        """
        reason: tools were installed
        state:
          path: "${stateFile}"
          format: json
          resumeFrom: next
        instructions:
          - Log out and back in.
        exit:
          code: 75
          message: Bootstrap paused.
        """
      )

      assert(
        spec == InstallerSpec.Interrupt(
          reason = "tools were installed",
          state = InterruptState("${stateFile}", Some(InterruptResumeFrom.Next)),
          instructions = Vector("Log out and back in."),
          exit = InterruptExit(code = Some(75), message = Some("Bootstrap paused."))
        )
      )

    test("rejects invalid interrupt specs"):
      val errors = validateInstallerSpec(
        "pause",
        "interrupt",
        """
        reason: tools were installed
        state:
          path: "${stateFile}"
        """
      )

      assert(errors.exists(_.message == "spec.plan[0].spec.state.format: is required"))

    test("decodes valid command specs"):
      val spec = decodeValidInstallerSpec(
        "post-install",
        "commands",
        """
        items:
          - name: enable-docker
            run: systemctl enable --now docker
            sudo: true
            when:
              commandExists: systemctl
        """
      )

      assert(
        spec == InstallerSpec.Commands(
          Vector(
            CommandItem(
              name = "enable-docker",
              run = "systemctl enable --now docker",
              sudo = Some(true),
              when = Some(
                Condition(
                  os = None,
                  commandExists = Some("systemctl"),
                  raw = RawYaml.MappingValue(scala.collection.immutable.VectorMap("commandExists" ->
                    RawYaml.StringValue("systemctl")))
                )
              )
            )
          )
        )
      )

    test("rejects invalid command specs"):
      val errors = validateInstallerSpec(
        "post-install",
        "commands",
        """
        items:
          - name: enable-docker
            run: ""
        """
      )

      assert(errors.exists(_.message == "spec.plan[0].spec.items[0].run: must not be empty"))

  private def decodeValidInstallerSpec(
      name: String,
      kind: String,
      spec: String
  ): InstallerSpec = loadValidatedInstaller(name, kind, spec) match
    case Right(entry) => InstallerSpecDecoder.decode(entry, index = 0) match
        case Right(spec)  => spec
        case Left(errors) =>
          fail(s"expected installer spec, found ${errors.map(_.message).mkString("; ")}")
    case Left(errors) =>
      fail(s"expected valid manifest, found ${errors.map(_.message).mkString("; ")}")

  private def validateInstallerSpec(
      name: String,
      kind: String,
      spec: String
  ): Vector[ManifestValidationError] = loadValidatedInstaller(name, kind, spec).left.toOption.get

  private def loadValidatedInstaller(
      name: String,
      kind: String,
      spec: String
  ): Either[Vector[ManifestValidationError], PlanEntry] =
    val tmp = os.temp.dir()
    try
      val config = tmp / "config.yaml"
      os.write(config, installerManifest(name, kind, spec))
      ManifestLoader.loadValidated(config.toNIO) match
        case Right(manifest)                                  => Right(manifest.spec.plan.head)
        case Left(error: ManifestLoadError.ValidationFailure) => Left(error.errors)
        case Left(error) => fail(s"expected validation result, found ${error.message}")
    finally os.remove.all(tmp)

  private def installerManifest(
      name: String,
      kind: String,
      spec: String
  ): String =
    val header = stripYaml(s"""
    apiVersion: initkit.io/v1alpha1
    kind: WorkstationProfile
    spec:
      vars:
        binDir: "$${HOME}/.local/bin"
        dotfilesConfig: "$${HOME}/.dotfiles/config.yaml"
        dotfilesDir: "$${HOME}/.dotfiles"
        home: "$${HOME}"
        nerdFontConfig: "$${HOME}/.config/nerd-fonts.yaml"
        stateFile: "$${HOME}/.local/state/initkit/state.json"
      plan:
        - name: $name
          kind: $kind
          spec:
    """)
    s"$header\n${indent(spec, 8)}"

  private def indent(source: String, spaces: Int): String =
    val padding = " " * spaces
    stripYaml(source).split("\n").map(line => s"$padding$line").mkString("\n")

  private def stripYaml(source: String): String =
    val lines        = source.replace("\r\n", "\n").split("\n").toVector
    val contentLines = lines.filter(_.trim.nonEmpty)
    val indent       = contentLines.map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)

    lines.map(line => if line.length >= indent then line.drop(indent) else line).mkString("\n").trim

  private def fail(message: String): Nothing = throw new java.lang.AssertionError(message)
