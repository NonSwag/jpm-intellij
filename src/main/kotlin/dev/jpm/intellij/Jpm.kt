package dev.jpm.intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Finding and running the jpm executable. */
object Jpm {
    const val MANIFEST = "project.jsonc"
    const val LOCKFILE = "project.lock"

    private val buildFiles = listOf("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")

    fun defaultLocationHint(): String = "~/.jpm/bin/jpm, else jpm on PATH"

    /** The jpm executable: the configured path, ~/.jpm/bin/jpm, or jpm on PATH. Null if there is none. */
    fun executable(): Path? {
        val configured = JpmSettings.get().state.jpmPath?.trim().orEmpty()
        if (configured.isNotEmpty()) return Path.of(configured).takeIf { Files.isRegularFile(it) }
        val home = System.getenv("JPM_HOME")?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".jpm")
        val names = if (SystemInfo.isWindows) listOf("jpm.exe", "jpm.cmd") else listOf("jpm")
        for (n in names) home.resolve("bin").resolve(n).takeIf { Files.isRegularFile(it) }?.let { return it }
        val path = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparatorChar).filter { it.isNotBlank() }
        for (d in path) for (n in names) Path.of(d).resolve(n).takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }?.let { return it }
        return null
    }

    fun commandLine(dir: Path, args: List<String>, color: Boolean = false): GeneralCommandLine {
        val exe = executable() ?: throw JpmNotFoundException()
        val cmd = GeneralCommandLine(exe.toString())
            .withParameters(args)
            .withWorkingDirectory(dir)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        if (color) cmd.withEnvironment("JPM_COLOR", "1")
        return cmd
    }

    /** Runs jpm and waits for it, cancellable through the indicator. */
    fun run(dir: Path, args: List<String>, indicator: ProgressIndicator?): ProcessOutput {
        val handler = CapturingProcessHandler(commandLine(dir, args))
        return if (indicator != null) handler.runProcessWithProgressIndicator(indicator) else handler.runProcess()
    }

    fun baseDir(project: Project): Path? = project.basePath?.let { Path.of(it) }

    /** Whether the project has jpm projects: in its folder, or in folders below it. */
    fun isJpmProject(project: Project): Boolean = JpmSync.get(project).roots().isNotEmpty()

    /** Folders that are never searched for jpm projects: build output, dependencies, VCS and IDE data. */
    private val skipped = setOf("node_modules", "build", "dist", "target", "out", "bin")

    /**
     * The jpm projects in [base]: [base] itself if it has a project.jsonc, otherwise every folder below it
     * that has one, down to [maxDepth] levels. A project's own folders are not searched further, since a
     * workspace finds its modules itself; hidden folders and build output are skipped.
     */
    fun findRoots(base: Path, maxDepth: Int = 4): List<Path> {
        if (isJpmProject(base)) return listOf(base)
        val out = ArrayList<Path>()
        fun walk(dir: Path, depth: Int) {
            if (depth > maxDepth) return
            val children = try {
                Files.newDirectoryStream(dir).use { s -> s.filter { Files.isDirectory(it) && !Files.isSymbolicLink(it) }.sorted() }
            } catch (e: java.io.IOException) {
                return
            }
            for (c in children) {
                val name = c.fileName.toString()
                if (name.startsWith(".") || name in skipped) continue
                if (isJpmProject(c)) out.add(c) else walk(c, depth + 1)
            }
        }
        walk(base, 1)
        return out
    }

    fun isJpmProject(dir: Path): Boolean = Files.isRegularFile(dir.resolve(MANIFEST))

    /** The Maven or Gradle build file that `jpm import` would read, if this is such a project. */
    fun importableBuild(dir: Path): String? =
        if (isJpmProject(dir)) null else buildFiles.firstOrNull { Files.isRegularFile(dir.resolve(it)) }
}

class JpmNotFoundException : RuntimeException(
    "jpm is not installed. Install it with: curl -fsSL https://github.com/NonSwag/jpm/releases/latest/download/install.sh | sh " +
        "(Windows: irm https://github.com/NonSwag/jpm/releases/latest/download/install.ps1 | iex), " +
        "or set its path in Settings | Build, Execution, Deployment | Build Tools | jpm."
)
