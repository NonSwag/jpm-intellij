package dev.jpm.intellij

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/** Loads jpm projects when they open, and offers to migrate Maven and Gradle projects. */
class JpmStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val dir = Jpm.baseDir(project) ?: return
        if (Jpm.isJpmProject(dir)) {
            JpmSync.get(project).reload()
            return
        }
        val build = Jpm.importableBuild(dir) ?: return
        val props = PropertiesComponent.getInstance(project)
        if (props.getBoolean(DONT_ASK)) return
        NotificationGroupManager.getInstance().getNotificationGroup("jpm")
            .createNotification(
                "Move this project to jpm?",
                "jpm can read $build and create project.jsonc from it. Nothing is changed until you confirm.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Migrate to jpm…") { JpmMigration.start(project) })
            .addAction(NotificationAction.createSimpleExpiring("Don't ask again") { props.setValue(DONT_ASK, true) })
            .notify(project)
    }

    companion object {
        private const val DONT_ASK = "dev.jpm.intellij.migration.dontAsk"
    }
}

/** Reloads when a project.jsonc or project.lock of the project changes on disk. */
class JpmFileListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val base = project.basePath ?: return
        val relevant = events.any { e ->
            val name = e.path.substringAfterLast('/')
            (name == Jpm.MANIFEST || name == Jpm.LOCKFILE) && e.path.startsWith(base)
        }
        if (relevant) JpmSync.get(project).scheduleReload()
    }
}
