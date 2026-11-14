package dev.jpm.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.PlatformProjectOpenProcessor
import com.intellij.projectImport.ProjectOpenProcessor
import javax.swing.Icon

/**
 * Opens folders with a project.jsonc (or the file itself) as jpm projects. The folder is opened the way the
 * platform opens any folder; JpmStartup then loads it from jpm.
 */
class JpmProjectOpenProcessor : ProjectOpenProcessor() {
    override val name: String = "jpm"

    override val icon: Icon = JpmIcons.Jpm

    override fun canOpenProject(file: VirtualFile): Boolean = projectDir(file) != null

    @Suppress("OVERRIDE_DEPRECATION")
    override fun doOpenProject(virtualFile: VirtualFile, projectToClose: Project?, forceOpenInNewFrame: Boolean): Project? {
        val dir = projectDir(virtualFile) ?: return null
        return PlatformProjectOpenProcessor.getInstance().doOpenProject(dir, projectToClose, forceOpenInNewFrame)
    }

    override suspend fun openProjectAsync(virtualFile: VirtualFile, projectToClose: Project?, forceOpenInNewFrame: Boolean): Project? {
        val dir = projectDir(virtualFile) ?: return null
        return PlatformProjectOpenProcessor.getInstance().openProjectAsync(dir, projectToClose, forceOpenInNewFrame)
    }

    private fun projectDir(file: VirtualFile): VirtualFile? = when {
        file.isDirectory -> file.takeIf { it.findChild(Jpm.MANIFEST) != null }
        file.name == Jpm.MANIFEST -> file.parent
        else -> null
    }
}
