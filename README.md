# jpm for IntelliJ IDEA

Opens [jpm](https://github.com/NonSwag/jpm) projects in IntelliJ IDEA (2026.1 and newer) and keeps them set
up: open a folder with a `project.jsonc` and it is ready to code in, with nothing to configure.

## What it does

- **Project setup.** One module per jpm module (and one for a workspace root), with `src/` as sources,
  `src/assets/` as resources, `test/` and `test/assets/` as test sources and resources, and `.cache/` and
  `dist/` excluded. Generated sources (the `BuildConfig` class from defines) are added too, so they resolve
  without a build.
- **Dependencies with sources.** Every package jpm resolved becomes a library with its sources jar, in the
  scope it is needed in: runtime dependencies as compile, `dev` as provided, `runtimeOnly` as runtime,
  `test` as test. Workspace modules depend on each other as modules.
- **JDK, automatically.** Each module gets the JDK jpm builds it with, registered in IntelliJ when it is not
  yet, and its language level from `"target"`. A JDK the project asks for that is not installed is
  installed by jpm, as on the command line.
- **Automatic reload.** Changes to `project.jsonc` or `project.lock` (from the editor, `jpm add`, a `git
  pull`) reload the project, and IntelliJ indexes what changed. **Tools | Reload jpm Project** reloads by
  hand.
- **A jpm tool window** on the right, like Gradle's and Maven's: build, test, start (one per entry point),
  dev, clean, release/bundle/native builds, install, update, outdated, publish, your scripts, and each
  module's dependencies. Double-click to run; output goes to the Run window, where the command can be
  rerun or stopped. Commands can also be saved as **jpm** run configurations.
- **Maven and Gradle migration.** Opening a Maven or Gradle project offers to move it to jpm (also
  **Tools | Migrate to jpm…**): it shows what `jpm import --dry-run` would write and what does not carry
  over, then runs `jpm import`. The Maven or Gradle files are not touched. See
  [MIGRATION.md](https://github.com/NonSwag/jpm/blob/main/MIGRATION.md).
- **New projects.** **File | New | Project | jpm** creates a project with `jpm init`.

## Requirements

jpm 0.5.0 or newer (for `jpm info --ide`), installed in `~/.jpm/bin` by the jpm install script or on `PATH`.
Another location can be set in **Settings | Build, Execution, Deployment | Build Tools | jpm**, which also
turns automatic reload off.

## How it works

The plugin runs `jpm info --json --ide` in the project folder and applies what it reports. jpm stays the
source of truth: the plugin never reads `project.jsonc` itself, so it understands every manifest jpm does.
IntelliJ's own build (Build | Build Project, running classes and tests from the gutter, debugging) works on
the configured modules and writes to `.cache/idea/`, apart from jpm's output.

## Building

```sh
./gradlew test buildPlugin     # build/distributions/jpm-intellij-<version>.zip
./gradlew runIde               # an IDE with the plugin installed
```

Install the zip with **Settings | Plugins | ⚙ | Install Plugin from Disk…**.

## License

MIT, see [LICENSE](LICENSE).
