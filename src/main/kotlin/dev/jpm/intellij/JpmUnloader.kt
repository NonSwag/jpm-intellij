package dev.jpm.intellij

import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.ui.RunContentManager
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

/**
 * Lets the plugin unload without a restart. IntelliJ removes the plugin's extensions itself, but a few
 * things it keeps would still point at the plugin's classes and keep them in memory:
 *
 * - Run tool window tabs of jpm commands (their run configuration, state and process listener);
 * - the temporary run configurations the jpm tool window creates;
 * - jpm notifications, whose actions are the plugin's code;
 * - a project load still running.
 *
 * Before the plugin unloads, this stops and closes all of them in every open project. Saved jpm run
 * configurations stay; IntelliJ keeps them as configurations of an unknown type until the plugin is back.
 */
class JpmUnloader : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return
        for (project in ProjectManager.getInstance().openProjects) release(project)
        release(null)
    }

    companion object {
        const val PLUGIN_ID = "dev.jpm"

        fun release(project: Project?) {
            if (project != null && !project.isDisposed) {
                project.serviceIfCreated<JpmSync>()?.stop()
                closeRunTabs(project)
                removeTemporaryConfigurations(project)
            }
            for (n in NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification::class.java, project)) {
                if (n.groupId == "jpm") n.expire()
            }
        }

        private fun closeRunTabs(project: Project) {
            val manager = RunContentManager.getInstance(project)
            for (descriptor in manager.allDescriptors.toList()) {
                val handler = descriptor.processHandler as? JpmProcessHandler ?: continue
                if (!handler.isProcessTerminated) handler.destroyProcess()
                for (executor in Executor.EXECUTOR_EXTENSION_NAME.extensionList) {
                    if (manager.removeRunContent(executor, descriptor)) break
                }
            }
        }

        private fun removeTemporaryConfigurations(project: Project) {
            val runManager = RunManager.getInstance(project)
            for (settings in runManager.allSettings.toList()) {
                if (settings.type is JpmConfigurationType && settings.isTemporary) runManager.removeConfiguration(settings)
            }
            if (runManager.selectedConfiguration?.type is JpmConfigurationType) runManager.selectedConfiguration = null
        }
    }
}
