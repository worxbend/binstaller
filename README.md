# initkit

Scala CLI + TUI starter using Mill, Li Haoyi libraries, and TamboUI.

## Requirements

- JDK 17 or newer
- A terminal that supports alternate-screen TUI apps

The repository includes the official Mill 1.1.7 bootstrap script, so a global `mill` install is not required.

## Commands

```bash
./mill app.run info
./mill app.run info --json
./mill app.run tui --name initkit --title "Initkit"
./mill app.test
```

Tamboui is currently consumed from Sonatype snapshot builds, configured in [build.mill](/home/worxbend/Workspace/AI/initkit/build.mill).
