package dev.jpm.intellij

import com.intellij.ide.projectWizard.generators.BuildSystemJavaNewProjectWizard
import com.intellij.ide.projectWizard.generators.JavaNewProjectWizard
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Panel
import java.nio.file.Path

/**
 * jpm as a build system in the Java New Project and New Module wizards, next to IntelliJ, Maven and Gradle.
 * There is no JDK to choose: jpm picks one, and installs it when it is missing.
 */
class JpmBuildSystemWizard : BuildSystemJavaNewProjectWizard {
    override val name: String = "jpm"

    override val ordinal: Int = 400

    override fun createStep(parent: JavaNewProjectWizard.Step): NewProjectWizardStep = Step(parent)

    private class Step(private val java: JavaNewProjectWizard.Step) : AbstractNewProjectWizardStep(java) {
        override fun setupUI(builder: Panel) {
            builder.row {
                comment(
                    if (context.isCreatingNewProject) "Creates project.jsonc, src/ and test/ with <code>jpm init</code>. " +
                        "jpm picks the JDK, and installs it when it is missing."
                    else "Creates a jpm module with <code>jpm init</code>. Inside a jpm workspace it is added to the " +
                        "workspace's modules; elsewhere it is a jpm project of its own, which the jpm tool window lists too."
                )
            }
        }

        override fun setupProject(project: Project) {
            val dir = Path.of(java.path, java.name)
            JpmModuleCreator.create(project, dir, java.name, addToWorkspace = !context.isCreatingNewProject)
        }
    }
}
