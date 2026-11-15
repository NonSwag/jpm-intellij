package dev.jpm.intellij

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class JpmToolWindowFactory : ToolWindowFactory, DumbAware {
    override suspend fun isApplicableAsync(project: Project): Boolean {
        val dir = Jpm.baseDir(project) ?: return false
        return JpmSync.get(project).roots().isNotEmpty() || Jpm.importableBuild(dir) != null
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JpmToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** A row of the tree: a group, a task that runs `jpm <args>`, a module or a dependency. */
private sealed class Item(val label: String, val icon: Icon?) {
    class Group(label: String, icon: Icon? = AllIcons.Nodes.ConfigFolder) : Item(label, icon)
    class Command(label: String, val args: List<String>, val hint: String? = null, val dir: Path? = null) :
        Item(label, AllIcons.Nodes.ConfigFolder)
    class ModuleNode(label: String, val version: String?) : Item(label, AllIcons.Nodes.Module)
    class ProjectNode(label: String, val path: String) : Item(label, JpmIcons.Jpm)
    class Dependency(label: String, val version: String?, val scope: String?) : Item(label, AllIcons.Nodes.PpLib)
    class Message(label: String) : Item(label, null)
}

private class JpmToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true), com.intellij.openapi.Disposable {
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = Renderer()
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = runSelected()
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && runSelected()) e.consume()
            }
        })
        setContent(ScrollPaneFactory.createScrollPane(tree, true))
        toolbar = createToolbar()
        project.messageBus.connect(this).subscribe(JpmSync.TOPIC, JpmSync.Listener { rebuild() })
        rebuild()
    }

    private fun createToolbar(): javax.swing.JComponent {
        val group = DefaultActionGroup()
        group.add(ActionManager.getInstance().getAction("Jpm.Reload"))
        group.add(object : DumbAwareAction("Run", "Run the selected jpm command", AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) {
                runSelected()
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selected() is Item.Command
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        group.add(ActionManager.getInstance().getAction("Jpm.Migrate"))
        group.addSeparator()
        val expander = DefaultTreeExpander(tree)
        group.add(CommonActionsManager.getInstance().createExpandAllAction(expander, tree))
        group.add(CommonActionsManager.getInstance().createCollapseAllAction(expander, tree))
        group.add(object : DumbAwareAction("jpm Settings", null, AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, JpmConfigurable::class.java)
            }
        })
        val bar = ActionManager.getInstance().createActionToolbar("JpmToolWindow", group, true)
        bar.targetComponent = this
        return bar.component
    }

    private fun selected(): Item? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Item

    private fun runSelected(): Boolean {
        val cmd = selected() as? Item.Command ?: return false
        JpmConfigurationType.run(project, cmd.args, cmd.dir)
        return true
    }

    private fun rebuild() {
        root.removeAllChildren()
        val sync = JpmSync.get(project)
        val infos = sync.infos
        val dir = Jpm.baseDir(project)
        when {
            dir != null && sync.roots().isEmpty() -> {
                val build = Jpm.importableBuild(dir)
                tree.emptyText.clear()
                tree.emptyText.appendLine("No jpm project here")
                if (build != null) {
                    tree.emptyText.appendLine("Migrate from $build", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                        JpmMigration.start(project)
                    }
                }
            }
            infos == null -> {
                tree.emptyText.clear()
                val err = sync.error
                when {
                    sync.loading -> tree.emptyText.appendLine("Loading…")
                    Jpm.executable() == null -> {
                        tree.emptyText.appendLine("jpm is not installed")
                        tree.emptyText.appendLine("Set its path", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                            ShowSettingsUtil.getInstance().showSettingsDialog(project, JpmConfigurable::class.java)
                        }
                    }
                    err != null -> {
                        tree.emptyText.appendLine("Could not load the project")
                        tree.emptyText.appendLine("Reload", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { sync.reload() }
                    }
                    else -> tree.emptyText.appendLine("Not loaded yet")
                }
            }
            else -> {
                sync.error?.let { root.add(DefaultMutableTreeNode(Item.Message("Last reload failed: " + it.lineSequence().first()))) }
                if (infos.size == 1) {
                    fill(infos[0], root)
                } else {
                    // Several jpm projects in nested folders: one section each, its commands run in its folder.
                    for (info in infos) {
                        val node = DefaultMutableTreeNode(Item.ProjectNode(info.name, sync.relative(Path.of(info.projectDir))))
                        root.add(node)
                        fill(info, node)
                    }
                }
            }
        }
        model.reload()
        if (infos != null) TreeUtil.expand(tree, if (infos.size == 1) 2 else 3)
    }

    private fun fill(info: JpmProjectInfo, top: DefaultMutableTreeNode) {
        val projectDir = Path.of(info.projectDir)
        fun node(item: Item, parent: DefaultMutableTreeNode = top) = DefaultMutableTreeNode(item).also { parent.add(it) }
        fun cmd(label: String, args: List<String>, hint: String?, parent: DefaultMutableTreeNode) =
            node(Item.Command(label, args, hint, projectDir), parent)

        val lifecycle = node(Item.Group("Lifecycle"))
        cmd("build", listOf("build"), "compile and package the jars", lifecycle)
        cmd("test", listOf("test"), "run the tests", lifecycle)
        val entries = info.modules.flatMap { it.main.orEmpty().keys }.distinct()
        if (entries.isEmpty() || entries == listOf("main")) {
            cmd("start", listOf("start"), "run main", lifecycle)
        } else {
            for (e in entries) cmd("start $e", listOf("start", e), "run $e", lifecycle)
        }
        cmd("dev", listOf("dev"), "run and restart on changes", lifecycle)
        cmd("clean", listOf("clean"), "remove build output", lifecycle)

        val build = node(Item.Group("Build"))
        cmd("build --release", listOf("build", "--release"), "with the release profile", build)
        cmd("build --bundle", listOf("build", "--bundle"), "one runnable jar with its dependencies", build)
        cmd("build --native", listOf("build", "--native"), "GraalVM native executable", build)

        val deps = node(Item.Group("Dependencies"))
        cmd("install", listOf("install"), "resolve and download", deps)
        cmd("update", listOf("update"), "newest versions within the ranges", deps)
        cmd("outdated", listOf("outdated"), "show newer versions", deps)

        val publishing = node(Item.Group("Publishing"))
        cmd("publish", listOf("publish"), "needs the jpm-publish extension", publishing)
        cmd("publish --local", listOf("publish", "--local"), "to ~/.m2/repository", publishing)

        val scripts = (info.scripts.orEmpty() + info.modules.flatMap { it.scripts.orEmpty() }).distinct()
        if (scripts.isNotEmpty()) {
            val group = node(Item.Group("Scripts"))
            for (s in scripts) cmd(s, listOf("run", s), "jpm run $s", group)
        }

        val modules = node(Item.Group(if (info.workspace) "Modules" else "Project", AllIcons.Nodes.ModuleGroup))
        for (m in info.modules) {
            val mod = node(Item.ModuleNode(m.name, m.version), modules)
            val declared = m.dependencies.orEmpty()
            for (scope in listOf("runtime", "dev", "test")) {
                val inScope = declared.filter { (it.scope ?: "runtime") == scope }
                if (inScope.isEmpty()) continue
                val g = node(Item.Group(scope, AllIcons.Nodes.Folder), mod)
                for (d in inScope) node(Item.Dependency(d.key, d.resolved ?: d.spec, null), g)
            }
            if (info.workspace) {
                for (e in m.main.orEmpty().keys) {
                    node(Item.Command("start " + (if (e == "main") "" else e).ifEmpty { "(main)" },
                        if (e == "main") listOf("start") else listOf("start", e), m.main?.get(e), Path.of(m.dir)), mod)
                }
            }
        }
    }

    override fun dispose() {}

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val item = (value as? DefaultMutableTreeNode)?.userObject as? Item ?: return
            when (item) {
                is Item.Command -> {
                    icon = AllIcons.Actions.Execute
                    append(item.label)
                    item.hint?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                is Item.ProjectNode -> {
                    icon = item.icon
                    append(item.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${item.path}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is Item.ModuleNode -> {
                    icon = item.icon
                    append(item.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    item.version?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                is Item.Dependency -> {
                    icon = item.icon
                    append(item.label)
                    item.version?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                is Item.Message -> {
                    icon = AllIcons.General.Warning
                    append(item.label, SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
                is Item.Group -> {
                    icon = item.icon
                    append(item.label)
                }
            }
        }
    }
}
