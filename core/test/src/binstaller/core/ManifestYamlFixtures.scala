package binstaller.core

import java.nio.file.Path

/** Manifest YAML documents the resolution and apply tests load through the real config parser. */
private[core] trait ManifestYamlFixtures:

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

  protected val sameRepoGitHubYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: same-repo
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |  vars: {}
      |  versions:
      |    jj: "0.40.0"
      |    jj-alt: "0.39.0"
      |  plan:
      |    - name: jujutsu
      |      kind: binary-tool
      |      spec:
      |        versionRef: jj
      |        installDir: "${appsDir}/jj"
      |        download:
      |          url: "https://github.com/jj-vcs/jj/releases/download/v${version}/jj-v${version}-x86_64-unknown-linux-musl.tar.gz"
      |          filename: jj.tar.gz
      |        executables:
      |          - path: bin/jj
      |    - name: jujutsu-alt
      |      kind: binary-tool
      |      spec:
      |        versionRef: jj-alt
      |        installDir: "${appsDir}/jj-alt"
      |        download:
      |          url: "https://github.com/jj-vcs/jj/releases/download/v${version}/jj-v${version}-aarch64-unknown-linux-musl.tar.gz"
      |          filename: jj-alt.tar.gz
      |        executables:
      |          - path: bin/jj-alt
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
