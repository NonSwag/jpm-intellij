package dev.jpm.intellij

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection

class JpmSchemaTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        // The bundled schema is read from the plugin's jar, outside the test project.
        val jar = com.intellij.util.PathUtil.getJarPathForClass(JpmSchemaProvider::class.java)
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, jar)
        myFixture.enableInspections(JsonSchemaComplianceInspection())
    }

    fun testProjectJsoncUsesTheBundledSchema() {
        val file = myFixture.configureByText(Jpm.MANIFEST, """{ "name": "demo" }""").virtualFile
        val schemas = JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file)
        assertTrue("schema for project.jsonc: $schemas", schemas.any { it.name == "project.schema.json" })
    }

    fun testValidManifestHasNoWarnings() {
        myFixture.configureByText(Jpm.MANIFEST, """
            // comments are fine in project.jsonc
            {
              "name": "demo",
              "version": "1.0.0",
              "java": "21",
              "main": "demo.App",
              "runtime": { "org.slf4j:slf4j-api": "2.0.16", "workspace:core": "*" },
              "test": { "org.junit.jupiter:junit-jupiter": { "version": "5.11.4" } },
              "hooks": { "postbuild:release": "upload" },
            }
        """.trimIndent())
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
        assertEmpty(warnings.map { it.description })
    }

    fun testMistakesAreFlagged() {
        myFixture.configureByText(Jpm.MANIFEST, """
            {
              "name": "demo",
              "dependencies": {},
              "layout": "ant",
              "runtime": { "a:b": { "version": "1", "optional": true } }
            }
        """.trimIndent())
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING).map { it.description.orEmpty() }
        assertTrue(warnings.toString(), warnings.any { "dependencies" in it })
        assertTrue(warnings.toString(), warnings.any { "ant" in it || "jpm" in it })
    }

    fun testCompletesFields() {
        myFixture.configureByText(Jpm.MANIFEST, """{ "name": "demo", "<caret>" }""")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertContainsElements(items, "runtime", "java", "workspace", "extensions")
    }

    fun testOtherJsonFilesAreLeftAlone() {
        val file = myFixture.configureByText("other.jsonc", """{ "anything": 1 }""").virtualFile
        val schemas = JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file)
        assertFalse(schemas.any { it.name == "project.schema.json" })
    }
}
