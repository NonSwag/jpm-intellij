package dev.jpm.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.testFramework.HeavyPlatformTestCase
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Loads a real jpm workspace (api, impl, app) the way the plugin does: `jpm info --json --ide` from the
 * jpm on this machine (or JPM_TEST_EXE), applied to a fresh project. Skipped where there is no jpm.
 */
class JpmProjectSetupTest : HeavyPlatformTestCase() {
    private lateinit var dir: Path

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jpm-workspace")
        fun write(rel: String, text: String) = dir.resolve(rel).also { Files.createDirectories(it.parent) }.writeText(text.trimIndent())
        write("project.jsonc", """{ "name": "demo", "version": "1.0.0", "workspace": ["api", "app"] }""")
        write("api/project.jsonc", """{ "name": "api", "group": "demo", "version": "1.0.0", "java": "21" }""")
        write("api/src/demo/api/Greeter.java", "package demo.api; public interface Greeter { String greet(); }")
        write("app/project.jsonc", """
            { "name": "app", "group": "demo", "version": "1.0.0", "java": "21", "target": "17", "main": "demo.App",
              "define": { "GREETING": { "type": "string", "default": "hi" } },
              "runtime": { "workspace:api": "*", "org.apache.commons:commons-lang3": "3.17.0" },
              "test": { "org.junit.jupiter:junit-jupiter-api": "5.11.4" } }
        """)
        write("app/src/demo/App.java", "package demo; public class App { public static void main(String[] a) {} }")
        write("app/src/assets/greeting.txt", "hello")
        write("app/test/demo/AppTest.java", "package demo; class AppTest {}")
    }

    fun testLoadsWorkspace() {
        System.getenv("JPM_TEST_EXE")?.let { JpmSettings.get().state.jpmPath = it }
        if (Jpm.executable() == null) {
            println("Skipped: no jpm (install it, or set JPM_TEST_EXE)")
            return
        }
        val out = Jpm.run(dir, listOf("info", "--json", "--ide"), null)
        assertEquals(out.stderr, 0, out.exitCode)
        val info = JpmProjectInfo.parse(out.stdout)

        ApplicationManager.getApplication().invokeAndWait { JpmProjectSetup.apply(project, info) }

        val modules = ModuleManager.getInstance(project).modules.associateBy { it.name }
        assertSameElements(modules.keys, "demo", "api", "app")

        val app = ModuleRootManager.getInstance(modules.getValue("app"))
        val folders = app.contentEntries.flatMap { it.sourceFolders.toList() }
        fun typeOf(rel: String) = folders.single { it.url.endsWith(rel) }.rootType
        assertEquals(JavaSourceRootType.SOURCE, typeOf("app/src"))
        assertEquals(JavaResourceRootType.RESOURCE, typeOf("app/src/assets"))
        assertEquals(JavaSourceRootType.TEST_SOURCE, typeOf("app/test"))
        assertEquals(JavaResourceRootType.TEST_RESOURCE, typeOf("app/test/assets"))
        assertTrue("generated BuildConfig folder", folders.any { it.url.endsWith("build/app/generated") })
        assertTrue(Files.isRegularFile(dir.resolve(".cache/build/app/generated/demo/BuildConfig.java")))

        val entries = app.orderEntries
        val api = entries.filterIsInstance<ModuleOrderEntry>().single()
        assertEquals("api", api.moduleName)
        assertEquals(DependencyScope.COMPILE, api.scope)
        val libs = entries.filterIsInstance<LibraryOrderEntry>().associateBy { it.libraryName!! }
        val lang = libs.getValue("jpm: org.apache.commons:commons-lang3:3.17.0")
        assertEquals(DependencyScope.COMPILE, lang.scope)
        assertTrue("sources attached", lang.library!!.getUrls(OrderRootType.SOURCES).isNotEmpty())
        assertEquals(DependencyScope.TEST, libs.getValue("jpm: org.junit.jupiter:junit-jupiter-api:5.11.4").scope)

        val sdk = ProjectRootManager.getInstance(project).projectSdk
        assertNotNull("project JDK", sdk)
        assertEquals(Path.of(info.modules.first().javaHome!!).normalize(), Path.of(sdk!!.homePath!!).normalize())
        assertEquals("17", app.getModuleExtension(com.intellij.openapi.roots.LanguageLevelModuleExtension::class.java)
            .languageLevel!!.toJavaVersion().feature.toString())

        // Loading again changes nothing and leaves no duplicates.
        ApplicationManager.getApplication().invokeAndWait { JpmProjectSetup.apply(project, info) }
        assertEquals(3, ModuleManager.getInstance(project).modules.size)
        val jpmLibraries = LibraryTablesRegistrar.getInstance().getLibraryTable(project).libraries.filter { it.name!!.startsWith("jpm: ") }
        assertEquals(jpmLibraries.map { it.name }.distinct().size, jpmLibraries.size)

        // A dependency removed from the project disappears from the IDE.
        val trimmed = JpmProjectInfo.parse(out.stdout)
        trimmed.modules.forEach { m -> m.ideDependencies = m.ideDependencies.orEmpty().filterNot { it.id.contains("commons-lang3") } }
        ApplicationManager.getApplication().invokeAndWait { JpmProjectSetup.apply(project, trimmed) }
        assertFalse(LibraryTablesRegistrar.getInstance().getLibraryTable(project).libraries.any { it.name!!.contains("commons-lang3") })
    }

    fun testLoadsJpmProjectsInNestedFolders() {
        System.getenv("JPM_TEST_EXE")?.let { JpmSettings.get().state.jpmPath = it }
        if (Jpm.executable() == null) {
            println("Skipped: no jpm (install it, or set JPM_TEST_EXE)")
            return
        }
        val base = Files.createTempDirectory("jpm-nested")
        try {
            fun write(rel: String, text: String) = base.resolve(rel).also { Files.createDirectories(it.parent) }.writeText(text)
            // two projects in subfolders, both with a module called "app"
            write("backend/project.jsonc", """{ "name": "app", "version": "1.0.0", "java": "21" }""")
            write("backend/src/b/App.java", "package b; public class App {}")
            write("tools/project.jsonc", """{ "name": "tools", "version": "1.0.0", "workspace": ["app"] }""")
            write("tools/app/project.jsonc", """{ "name": "app", "version": "1.0.0", "java": "21" }""")
            write("tools/app/src/t/App.java", "package t; public class App {}")

            val roots = Jpm.findRoots(base)
            assertEquals(listOf(base.resolve("backend"), base.resolve("tools")), roots)
            val infos = roots.map { dir ->
                val out = Jpm.run(dir, listOf("info", "--json", "--ide"), null)
                assertEquals(out.stderr, 0, out.exitCode)
                JpmProjectInfo.parse(out.stdout)
            }
            ApplicationManager.getApplication().invokeAndWait { JpmProjectSetup.apply(project, infos) }

            val modules = ModuleManager.getInstance(project).modules.associateBy { it.name }
            assertSameElements(modules.keys, "app", "tools", "tools.app")
            fun sources(name: String) = ModuleRootManager.getInstance(modules.getValue(name)).sourceRootUrls.filter { it.endsWith("/src") }
            assertTrue(sources("app").single().endsWith("backend/src"))
            assertTrue(sources("tools.app").single().endsWith("tools/app/src"))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    override fun tearDown() {
        try {
            ApplicationManager.getApplication().invokeAndWait {
                com.intellij.openapi.application.WriteAction.run<RuntimeException> {
                    val table = ProjectJdkTable.getInstance()
                    table.allJdks.filter { it.name.endsWith("(jpm)") }.forEach { table.removeJdk(it) }
                }
            }
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }
}
