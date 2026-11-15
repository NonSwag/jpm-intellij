package dev.jpm.intellij

import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GitNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Panel
import javax.swing.Icon

/**
 * File | New | Project | jpm: a folder with project.jsonc, src/<name>/App.java and a test, made by
 * `jpm init`. There is no JDK to choose: jpm picks the newest installed one and installs one if needed.
 */
class JpmNewProjectWizard : GeneratorNewProjectWizard {
    override val id: String = "jpm"
    override val name: String = "jpm"
    override val icon: Icon = JpmIcons.Jpm
    override val description: String = "A Java project built with jpm. The JDK is picked and installed by jpm."

    override fun createStep(context: WizardContext): NewProjectWizardStep =
        RootNewProjectWizardStep(context)
            .nextStep(::NewProjectWizardBaseStep)
            .nextStep(::GitNewProjectWizardStep)
            .nextStep(::Step)

    private class Step(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
        override fun setupUI(builder: Panel) {
            builder.row {
                comment("Creates project.jsonc, src/ and test/ with <code>jpm init</code>. " +
                    "jpm picks the JDK, and installs it when it is missing.")
            }
        }

        override fun setupProject(project: Project) {
            val dir = Jpm.baseDir(project) ?: return
            if (Jpm.isJpmProject(dir)) return
            JpmModuleCreator.create(project, dir, dir.fileName.toString(), addToWorkspace = false)
        }
    }
}
