package dev.jpm.intellij

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Completion, documentation and validation for every project.jsonc, from the schema bundled with the
 * plugin (a copy of jpm's schema/project.schema.json). Manifests whose "$schema" names jpm's published
 * schema get the bundled copy too, so nothing is downloaded.
 */
class JpmSchemaProviderFactory : JsonSchemaProviderFactory, DumbAware {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(JpmSchemaProvider)
}

object JpmSchemaProvider : JsonSchemaFileProvider {
    const val SCHEMA_URL = "https://github.com/java-package-manager/jpm/releases/latest/download/project.schema.json"
    private const val RESOURCE = "/schemas/project.schema.json"

    override fun isAvailable(file: VirtualFile): Boolean = file.name == Jpm.MANIFEST

    override fun getName(): String = "jpm project.jsonc"

    override fun getPresentableName(): String = "jpm"

    override fun getSchemaFile(): VirtualFile? = JsonSchemaProviderFactory.getResourceFile(JpmSchemaProvider::class.java, RESOURCE)

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema

    override fun getRemoteSource(): String = SCHEMA_URL
}
