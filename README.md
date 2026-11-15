# jpm for IntelliJ IDEA

Opens [jpm](https://github.com/java-package-manager/jpm) projects in IntelliJ IDEA (2026.1 and newer) and keeps them set
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
- **Editing `project.jsonc`.** Completion, documentation and validation from jpm's JSON Schema, bundled
  with the plugin so it works offline and without a `"$schema"` line: unknown fields, wrong dependency
  keys, misspelled options and invalid values are underlined as you type.
- **Nested projects.** Every jpm project in the opened folder is loaded: the folder's own, projects nested
  inside it (test fixtures, examples), and projects in subfolders of a folder that is not one (a monorepo, a
  backend next to a frontend). A workspace's modules load with their workspace. Each project gets its own
  section in the tool window, and its commands run in its folder. Hidden folders and build output are not
  searched. A nested project that fails to load (a deliberately broken fixture, say) keeps its last loaded
  state and is reported, without holding up the others.
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
  [MIGRATION.md](https://github.com/java-package-manager/jpm/blob/main/MIGRATION.md).
- **New projects.** **File | New | Project | jpm** creates a project with `jpm init`.

## Installing

Add the plugin's update site once, and IntelliJ installs it and keeps it up to date like any other plugin:
**Settings | Plugins | ⚙ | Manage Plugin Repositories… | +**, then

```
https://github.com/java-package-manager/jpm-intellij/releases/latest/download/updatePlugins.xml
```

and search for **jpm** on the Marketplace tab. Each release's zip can also be installed by hand with
**Settings | Plugins | ⚙ | Install Plugin from Disk…**.

## Requirements

jpm 0.5.0 or newer (for `jpm info --ide`), installed in `~/.jpm/bin` by the jpm install script or on `PATH`.
Another location can be set in **Settings | Build, Execution, Deployment | Build Tools | jpm**, which also
turns automatic reload off.

## How it works

The plugin runs `jpm info --json --ide` in the project folder and applies what it reports. jpm stays the
source of truth: the plugin never reads `project.jsonc` itself, so it understands every manifest jpm does.
IntelliJ's own build (Build | Build Project, running classes and tests from the gutter, debugging) works on
the configured modules and writes to `.cache/idea/`, apart from jpm's output.

## The schema

`src/main/resources/schemas/project.schema.json` is a copy of jpm's
[`schema/project.schema.json`](https://github.com/java-package-manager/jpm/blob/main/schema/project.schema.json). CI
warns when it differs from the schema of jpm's latest release; copy it over when jpm's changes.

## Building

```sh
./gradlew test buildPlugin     # build/distributions/jpm-intellij-<version>.zip
./gradlew runIde               # an IDE with the plugin installed
```

Install the zip with **Settings | Plugins | ⚙ | Install Plugin from Disk…**.

## Releasing

Change `pluginVersion` in `gradle.properties` and push to `main`: a version that has no release yet is
tagged (plain, no `v` prefix) and released by that run; there is no tag to push by hand. A version is
never released twice, and versions like `1.0.0-rc1` become GitHub prereleases.

CI tests and verifies the plugin, then publishes a GitHub release with the zip, `SHA256SUMS` and the
`updatePlugins.xml` that installed IDEs update from. With a `JETBRAINS_MARKETPLACE_TOKEN` secret it also
publishes to the JetBrains Marketplace (the first upload has to be made by hand on the Marketplace), signed
when `PLUGIN_SIGNING_CERTIFICATE_CHAIN`, `PLUGIN_SIGNING_PRIVATE_KEY` and
`PLUGIN_SIGNING_PRIVATE_KEY_PASSWORD` are set.

## License

MIT, see [LICENSE](LICENSE).
