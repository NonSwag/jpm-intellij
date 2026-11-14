package dev.jpm.intellij

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.projectImport.ProjectOpenProcessor
import javax.swing.Icon

/** Opens folders with a project.jsonc (or the file itself) as jpm projects. */
class JpmProjectOpenProcessor : ProjectOpenProcessor() {
    override val name: String = "jpm"

    override val icon: Icon = JpmIcons.Jpm

    override fun canOpenProject(file: VirtualFile): Boolean = projectDir(file) != null

    override fun doOpenProject(virtualFile: VirtualFile, projectToClose: Project?, forceOpenInNewFrame: Boolean): Project? {
        val dir = projectDir(virtualFile) ?: return null
        val isNew = dir.findChild(".idea") == null
        return ProjectManagerEx.getInstanceEx().openProject(dir.toNioPath(), OpenProjectTask {
            this.forceOpenInNewFrame = forceOpenInNewFrame
            this.projectToClose = projectToClose
            this.isNewProject = isNew
            this.runConfigurators = false
        })
        // JpmStartup loads the project once it is open.
    }

    private fun projectDir(file: VirtualFile): VirtualFile? = when {
        file.isDirectory -> file.takeIf { it.findChild(Jpm.MANIFEST) != null }
        file.name == Jpm.MANIFEST -> file.parent
        else -> null
    }
}
