package dev.jpm.intellij

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Loads the jpm project into IntelliJ, on request or when its manifests change. */
@Service(Service.Level.PROJECT)
class JpmSync(private val project: Project) : Disposable {
    /** The project as jpm last reported it, or null before the first load. */
    @Volatile
    var info: JpmProjectInfo? = null
        private set

    /** The last load's error, shown in the tool window. */
    @Volatile
    var error: String? = null
        private set

    @Volatile
    var loading: Boolean = false
        private set

    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var lastInputs: String? = null

    /** Loads the project now. Explicit reloads always run; automatic ones only when the manifests changed. */
    fun reload(explicit: Boolean = true) {
        if (project.isDisposed || !Jpm.isJpmProject(project)) return
        if (!explicit && inputsHash() == lastInputs && info != null) return
        if (!running.compareAndSet(false, true)) {
            again.set(true)
            return
        }
        loading = true
        publish()
        object : Task.Backgroundable(project, "Loading jpm project", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "jpm info"
                val dir = Jpm.baseDir(project) ?: return
                val out = Jpm.run(dir, listOf("info", "--json", "--ide"), indicator)
                if (indicator.isCanceled) return
                if (out.exitCode != 0) {
                    fail(out.stderr.ifBlank { out.stdout }.trim().lines().filterNot { it.startsWith("Picked up ") }.joinToString("\n"))
                    return
                }
                val parsed = JpmProjectInfo.parse(out.stdout)
                ApplicationManager.getApplication().invokeAndWait({
                    if (!project.isDisposed) JpmProjectSetup.apply(project, parsed)
                }, com.intellij.openapi.application.ModalityState.nonModal())
                info = parsed
                error = null
                // jpm info may have written project.lock; that is part of this load, not a new change.
                lastInputs = inputsHash()
                parsed.modules.mapNotNull { m -> m.javaError?.let { "${m.name}: $it" } }.takeIf { it.isNotEmpty() }?.let {
                    notify("The JDK for this project is not available", it.joinToString("\n"), NotificationType.WARNING)
                }
            }

            override fun onThrowable(error: Throwable) {
                if (error is JpmNotFoundException) fail(error.message ?: "jpm is not installed")
                else {
                    logger<JpmSync>().warn(error)
                    fail(error.message ?: error.toString())
                }
            }

            override fun onFinished() {
                loading = false
                running.set(false)
                publish()
                if (again.getAndSet(false)) reload(explicit = false)
            }
        }.queue()
    }

    /** Reloads shortly after a change, so a burst of file events loads once. */
    fun scheduleReload() {
        if (!JpmSettings.get().state.autoReload) return
        alarm.cancelAllRequests()
        alarm.addRequest({ reload(explicit = false) }, 500)
    }

    private fun fail(message: String) {
        error = message
        val actions = if (Jpm.executable() == null) {
            listOf(NotificationAction.createSimple("Settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, JpmConfigurable::class.java)
            })
        } else {
            listOf(NotificationAction.createSimple("Reload") { reload() })
        }
        notify("Could not load the jpm project", message, NotificationType.ERROR, actions)
    }

    private fun notify(title: String, content: String, type: NotificationType, actions: List<NotificationAction> = emptyList()) {
        val n = NotificationGroupManager.getInstance().getNotificationGroup("jpm").createNotification(title, content, type)
        actions.forEach { n.addAction(it) }
        n.notify(project)
    }

    private fun publish() {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).changed()
        }, project.disposed)
    }

    /** A hash of every manifest and lockfile of the project, to see whether a reload would change anything. */
    private fun inputsHash(): String {
        val dir = Jpm.baseDir(project) ?: return ""
        val md = MessageDigest.getInstance("SHA-256")
        val files = buildList {
            add(dir.resolve(Jpm.MANIFEST))
            add(dir.resolve(Jpm.LOCKFILE))
            info?.modules?.forEach { add(Path.of(it.dir).resolve(Jpm.MANIFEST)) }
        }.distinct()
        for (f in files) {
            md.update(f.toString().toByteArray())
            if (Files.isRegularFile(f)) md.update(Files.readAllBytes(f))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    override fun dispose() {}

    fun interface Listener {
        fun changed()
    }

    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("jpm project", Listener::class.java)

        fun get(project: Project): JpmSync = project.service()
    }
}
