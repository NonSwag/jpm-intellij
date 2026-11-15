package dev.jpm.intellij

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * Creates jpm projects and modules with `jpm init`, for the New Project and New Module wizards.
 *
 * A module created inside a jpm workspace is added to the workspace's "workspace" list, so it is one of
 * its modules; anywhere else it is a jpm project of its own, which the plugin loads like any nested project.
 */
object JpmModuleCreator {
    /** What [create] did with a new module, for the wizard and tests. */
    enum class Result { PROJECT, WORKSPACE_MODULE, NESTED_PROJECT, FAILED }

    fun create(project: Project, dir: Path, name: String, addToWorkspace: Boolean): Result {
        if (Jpm.isJpmProject(dir)) {
            Messages.showErrorDialog(project, "$dir already has a ${Jpm.MANIFEST}.", "jpm")
            return Result.FAILED
        }
        Files.createDirectories(dir)
        val error = ProgressManager.getInstance().runProcessWithProgressSynchronously<String?, RuntimeException>({
            initIn(dir, name)
        }, "Creating jpm Project", true, project)
        if (error != null) {
            Messages.showErrorDialog(project, error.trim(), "jpm init Failed")
            return Result.FAILED
        }
        val result = if (addToWorkspace) addToEnclosingWorkspace(project, dir) else Result.PROJECT
        LocalFileSystem.getInstance().refreshNioFiles(listOf(dir), true, true) {
            val sync = JpmSync.get(project)
            sync.rootsChanged()
            sync.reload()
        }
        return result
    }

    /** Runs `jpm init <name>` in [dir]; returns jpm's error, or null. */
    fun initIn(dir: Path, name: String): String? = try {
        val out = Jpm.run(dir, listOf("init", name), ProgressManager.getInstance().progressIndicator)
        if (out.exitCode == 0) null else out.stderr.ifBlank { out.stdout }
    } catch (e: JpmNotFoundException) {
        e.message
    }

    /** The nearest folder above [dir], within the project, whose project.jsonc is a workspace. */
    fun enclosingWorkspace(project: Project, dir: Path): Path? {
        val base = Jpm.baseDir(project)?.toAbsolutePath()?.normalize()
        var d = dir.toAbsolutePath().normalize().parent
        while (d != null && (base == null || d.startsWith(base))) {
            val manifest = d.resolve(Jpm.MANIFEST)
            if (Files.isRegularFile(manifest)) return if (workspaceArray(project, manifest) != null) d else null
            d = d.parent
        }
        return null
    }

    /**
     * Adds [dir] to the "workspace" list of the workspace it is in, keeping the file's comments and layout.
     * Without one, the new module is a jpm project of its own.
     */
    fun addToEnclosingWorkspace(project: Project, dir: Path): Result {
        val root = enclosingWorkspace(project, dir) ?: return Result.NESTED_PROJECT
        val manifest = root.resolve(Jpm.MANIFEST)
        val rel = root.relativize(dir.toAbsolutePath().normalize()).toString().replace('\\', '/')
        val array = workspaceArray(project, manifest) ?: return Result.NESTED_PROJECT
        if (array.valueList.any { (it as? JsonStringLiteral)?.value == rel }) return Result.WORKSPACE_MODULE
        val file = array.containingFile
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return Result.NESTED_PROJECT
        WriteCommandAction.runWriteCommandAction(project, "Add $rel to the jpm Workspace", null, {
            val last = array.valueList.lastOrNull()
            val quoted = "\"" + rel.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            if (last == null) document.insertString(array.textRange.startOffset + 1, quoted)
            else document.insertString(last.textRange.endOffset, ", $quoted")
            PsiDocumentManager.getInstance(project).commitDocument(document)
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveDocument(document)
        })
        return Result.WORKSPACE_MODULE
    }

    private fun workspaceArray(project: Project, manifest: Path): JsonArray? {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(manifest) ?: return null
        val psi = PsiManager.getInstance(project).findFile(vf) as? JsonFile ?: return null
        val top = psi.topLevelValue as? JsonObject ?: return null
        return top.findProperty("workspace")?.value as? JsonArray
    }
}
