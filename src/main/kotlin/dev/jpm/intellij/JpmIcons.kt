package dev.jpm.intellij

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object JpmIcons {
    @JvmField
    val Jpm: Icon = IconLoader.getIcon("/icons/jpm.svg", JpmIcons::class.java)

    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/toolWindow.svg", JpmIcons::class.java)
}
