package dev.jpm.intellij

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Moves a Maven or Gradle project to jpm: shows what `jpm import --dry-run` would write and what does not
 * carry over, then runs `jpm import` and loads the result. The Maven or Gradle files are left untouched.
 */
object JpmMigration {
    fun start(project: Project) {
        val dir = Jpm.baseDir(project) ?: return
        val build = Jpm.importableBuild(dir) ?: return
        object : Task.Modal(project, "Reading $build", true) {
            var preview: String = ""
            var failed = false

            override fun run(indicator: ProgressIndicator) {
                val out = Jpm.run(dir, listOf("import", "--dry-run"), indicator)
                preview = clean(out.stdout + "\n" + out.stderr)
                failed = out.exitCode != 0
            }

            override fun onSuccess() {
                if (failed) {
                    Messages.showErrorDialog(project, preview, "jpm Could Not Read $build")
                    return
                }
                if (PreviewDialog(project, build, preview).showAndGet()) runImport(project)
            }

            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(project, error.message ?: error.toString(), "Migrate to jpm")
            }
        }.queue()
    }

    private fun runImport(project: Project) {
        val dir = Jpm.baseDir(project) ?: return
        object : Task.Backgroundable(project, "Migrating to jpm", true) {
            override fun run(indicator: ProgressIndicator) {
                val out = Jpm.run(dir, listOf("import"), indicator)
                val text = clean(out.stderr + "\n" + out.stdout)
                val group = NotificationGroupManager.getInstance().getNotificationGroup("jpm")
                if (out.exitCode != 0) {
                    group.createNotification("Migration to jpm failed", notificationText(text), NotificationType.ERROR).notify(project)
                    return
                }
                LocalFileSystem.getInstance().refreshNioFiles(listOf(dir.resolve(Jpm.MANIFEST), dir.resolve(Jpm.LOCKFILE)))
                group.createNotification(
                    "Migrated to jpm",
                    "project.jsonc was created. The Maven or Gradle files are unchanged; remove them once jpm builds " +
                        "the project. See MIGRATION.md in the jpm repository for what to check.",
                    NotificationType.INFORMATION,
                ).notify(project)
                ApplicationManager.getApplication().invokeLater({ JpmSync.get(project).reload() }, project.disposed)
            }
        }.queue()
    }

    private fun clean(s: String): String =
        s.lines().filterNot { it.startsWith("Picked up ") }.joinToString("\n").replace(Regex("\u001b\\[[0-9;]*m"), "").trim()

    private class PreviewDialog(project: Project, private val build: String, private val preview: String) : DialogWrapper(project) {
        init {
            title = "Migrate to jpm"
            setOKButtonText("Migrate")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
            panel.add(
                JBLabel("<html>jpm read <b>$build</b>. This is what it would write, followed by what has no jpm " +
                    "equivalent. Your Maven or Gradle files stay as they are.</html>"),
                BorderLayout.NORTH,
            )
            val text = JBTextArea(preview).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
                caretPosition = 0
            }
            panel.add(JBScrollPane(text).apply { preferredSize = Dimension(JBUI.scale(720), JBUI.scale(440)) }, BorderLayout.CENTER)
            return panel
        }
    }
}
