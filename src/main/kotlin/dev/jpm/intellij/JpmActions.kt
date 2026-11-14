package dev.jpm.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

class ReloadAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { JpmSync.get(it).reload() }
    }

    override fun update(e: AnActionEvent) {
        val p = e.project
        e.presentation.isEnabledAndVisible = p != null && Jpm.isJpmProject(p)
        if (p != null) e.presentation.isEnabled = !JpmSync.get(p).loading
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class MigrateAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { JpmMigration.start(it) }
    }

    override fun update(e: AnActionEvent) {
        val dir = e.project?.let { Jpm.baseDir(it) }
        e.presentation.isEnabledAndVisible = dir != null && Jpm.importableBuild(dir) != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
