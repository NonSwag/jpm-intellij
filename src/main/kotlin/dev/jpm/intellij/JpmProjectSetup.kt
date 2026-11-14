package dev.jpm.intellij

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModifiableModuleModel
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.LanguageLevelModuleExtension
import com.intellij.openapi.roots.LanguageLevelProjectExtension
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.impl.ModifiableModelCommitter
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTable
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.pom.java.LanguageLevel
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Applies what `jpm info --ide` reports to the IntelliJ project: one module per jpm module (plus one for
 * the workspace root), its folders, its dependencies as project libraries with sources, its JDK and
 * language level. Everything jpm set up before and no longer reports is removed again.
 */
object JpmProjectSetup {
    const val LIBRARY_PREFIX = "jpm: "
    private const val MODULES_KEY = "dev.jpm.intellij.modules"

    /** Must run on the EDT. */
    fun apply(project: Project, info: JpmProjectInfo) {
        WriteAction.run<RuntimeException> { applyInWriteAction(project, info) }
    }

    private fun applyInWriteAction(project: Project, info: JpmProjectInfo) {
        val projectDir = Path.of(info.projectDir)
        val sdks = HashMap<String, Sdk>()
        fun sdkFor(m: JpmModuleInfo): Sdk? = m.javaHome?.let { home -> sdks.getOrPut(home) { findOrCreateSdk(home, m.java) } }

        // Project JDK and language level: those of the first module (jpm builds every module with its own).
        info.modules.firstOrNull()?.let { first ->
            sdkFor(first)?.let { ProjectRootManager.getInstance(project).projectSdk = it }
            languageLevel(first.release)?.let { LanguageLevelProjectExtension.getInstance(project).languageLevel = it }
        }
        ProjectRootManager.getInstance(project).projectSdk?.let { sdks.putIfAbsent(it.homePath ?: "", it) }

        val libraries = updateLibraries(project, info)

        val moduleModel = ModuleManager.getInstance(project).getModifiableModel()
        val previous = PropertiesComponent.getInstance(project).getList(MODULES_KEY).orEmpty().toSet()
        val wanted = LinkedHashMap<String, Path>()
        if (info.workspace) wanted[info.name] = projectDir
        for (m in info.modules) wanted[m.name] = Path.of(m.dir)

        // Modules jpm made earlier that are gone, and modules IntelliJ made for these folders on its own.
        for (module in moduleModel.modules.toList()) {
            val name = module.name
            if (name in wanted) continue
            val roots = ModuleRootManager.getInstance(module).contentRootUrls.toList()
            val overlaps = roots.isEmpty() || roots.any { url -> wanted.values.any { VfsUtilCore.pathToUrl(it.toString()) == url } }
            if (name in previous || overlaps) moduleModel.disposeModule(module)
        }

        val modules = LinkedHashMap<String, Module>()
        for ((name, dir) in wanted) modules[name] = moduleModel.findModuleByName(name) ?: newModule(moduleModel, projectDir, name)

        val rootModels = ArrayList<ModifiableRootModel>()
        if (info.workspace) {
            val root = ModuleRootManager.getInstance(modules.getValue(info.name)).modifiableModel
            root.clear()
            val entry = root.addContentEntry(url(projectDir))
            val keep = info.modules.mapNotNull { it.generatedSourceDir?.let(Path::of) }
            for (ex in listOf(projectDir.resolve(".cache"), projectDir.resolve("dist"))) exclude(entry, ex, keep)
            info.modules.firstOrNull()?.let { sdkFor(it) }?.let { root.sdk = it } ?: root.inheritSdk()
            rootModels.add(root)
        }
        for (m in info.modules) {
            val model = ModuleRootManager.getInstance(modules.getValue(m.name)).modifiableModel
            configureModule(model, m, sdkFor(m), modules, libraries, projectDir)
            rootModels.add(model)
        }
        ModifiableModelCommitter.multiCommit(rootModels.toTypedArray(), moduleModel)
        PropertiesComponent.getInstance(project).setList(MODULES_KEY, wanted.keys.toList())
    }

    private fun newModule(model: ModifiableModuleModel, projectDir: Path, name: String): Module {
        val iml = projectDir.resolve(".idea").resolve("modules").resolve("$name.iml")
        return model.newModule(iml.toString(), JavaModuleType.getModuleType().id)
    }

    private fun configureModule(
        model: ModifiableRootModel, m: JpmModuleInfo, sdk: Sdk?, modules: Map<String, Module>,
        libraries: Map<String, Library>, projectDir: Path,
    ) {
        model.clear()
        val dir = Path.of(m.dir)
        val entry = model.addContentEntry(url(dir))
        fun folder(path: String?, type: Any) {
            if (path == null) return
            val p = Path.of(path)
            val e = if (p.startsWith(dir)) entry else model.addContentEntry(url(p))
            when (type) {
                JavaSourceRootType.SOURCE -> e.addSourceFolder(url(p), JavaSourceRootType.SOURCE)
                JavaSourceRootType.TEST_SOURCE -> e.addSourceFolder(url(p), JavaSourceRootType.TEST_SOURCE)
                JavaResourceRootType.RESOURCE -> e.addSourceFolder(url(p), JavaResourceRootType.RESOURCE)
                JavaResourceRootType.TEST_RESOURCE -> e.addSourceFolder(url(p), JavaResourceRootType.TEST_RESOURCE)
            }
        }
        folder(m.sourceDir, JavaSourceRootType.SOURCE)
        folder(m.resourceDir, JavaResourceRootType.RESOURCE)
        folder(m.testDir, JavaSourceRootType.TEST_SOURCE)
        folder(m.testResourceDir, JavaResourceRootType.TEST_RESOURCE)
        m.generatedSourceDir?.let { gen ->
            val p = Path.of(gen)
            val e = if (p.startsWith(dir)) entry else model.addContentEntry(url(p))
            e.addSourceFolder(url(p), JavaSourceRootType.SOURCE, JpsJavaExtensionService.getInstance().createSourceRootProperties("", true))
        }
        val keep = listOfNotNull(m.generatedSourceDir?.let(Path::of))
        for (ex in m.excludeDirs.orEmpty()) exclude(entry, Path.of(ex), keep)

        if (sdk != null) model.sdk = sdk else model.inheritSdk()
        languageLevel(m.release)?.let { model.getModuleExtension(LanguageLevelModuleExtension::class.java).languageLevel = it }

        // IntelliJ's own builder writes elsewhere than jpm, so the two never trip over each other.
        val out = model.getModuleExtension(CompilerModuleExtension::class.java)
        out.inheritCompilerOutputPath(false)
        out.setCompilerOutputPath(url(projectDir.resolve(".cache").resolve("idea").resolve(m.name).resolve("classes")))
        out.setCompilerOutputPathForTests(url(projectDir.resolve(".cache").resolve("idea").resolve(m.name).resolve("test-classes")))
        out.setExcludeOutput(true)

        for (d in m.ideDependencies.orEmpty()) {
            val scope = when (d.scope) {
                "provided" -> DependencyScope.PROVIDED
                "runtime" -> DependencyScope.RUNTIME
                "test" -> DependencyScope.TEST
                else -> DependencyScope.COMPILE
            }
            val target = d.module
            if (target != null) {
                modules[target]?.let { model.addModuleOrderEntry(it).scope = scope }
            } else {
                libraries[libraryName(d)]?.let { model.addLibraryEntry(it).scope = scope }
            }
        }
    }

    /**
     * Excludes [dir], except for the folders in [keep] inside it: then only its other children are
     * excluded, so generated sources under .cache/ stay visible.
     */
    internal fun exclude(entry: ContentEntry, dir: Path, keep: List<Path>) {
        for (p in excludedPaths(dir, keep)) entry.addExcludeFolder(url(p))
    }

    internal fun excludedPaths(dir: Path, keep: List<Path>): List<Path> {
        val inside = keep.filter { it.startsWith(dir) }
        if (inside.isEmpty()) return listOf(dir)
        if (inside.any { it == dir } || !dir.isDirectory()) return emptyList()
        return dir.listDirectoryEntries().filter { Files.isDirectory(it) }.sorted().flatMap { excludedPaths(it, inside) }
    }

    /** One project library per resolved package, named "jpm: group:artifact:version". */
    private fun updateLibraries(project: Project, info: JpmProjectInfo): Map<String, Library> {
        val table: LibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        val tableModel = table.modifiableModel
        val wanted = LinkedHashMap<String, JpmIdeDependency>()
        for (m in info.modules) for (d in m.ideDependencies.orEmpty()) if (d.module == null) wanted.putIfAbsent(libraryName(d), d)
        for (lib in tableModel.libraries.toList()) {
            val name = lib.name ?: continue
            if (name.startsWith(LIBRARY_PREFIX) && name !in wanted) tableModel.removeLibrary(lib)
        }
        val out = LinkedHashMap<String, Library>()
        for ((name, d) in wanted) {
            val lib = tableModel.getLibraryByName(name) ?: tableModel.createLibrary(name)
            val libModel = lib.modifiableModel
            for (type in listOf(OrderRootType.CLASSES, OrderRootType.SOURCES)) {
                for (u in libModel.getUrls(type)) libModel.removeRoot(u, type)
            }
            for (jar in d.jars.orEmpty()) libModel.addRoot(rootUrl(Path.of(jar)), OrderRootType.CLASSES)
            d.sources?.let { libModel.addRoot(rootUrl(Path.of(it)), OrderRootType.SOURCES) }
            libModel.commit()
            out[name] = lib
        }
        tableModel.commit()
        return out
    }

    private fun libraryName(d: JpmIdeDependency): String = LIBRARY_PREFIX + d.id + (d.version?.let { ":$it" } ?: "")

    /** The JDK at [home], registered with IntelliJ if it is not yet. */
    private fun findOrCreateSdk(home: String, spec: String?): Sdk {
        val table = ProjectJdkTable.getInstance()
        val javaSdk = JavaSdk.getInstance()
        val normalized = Path.of(home).normalize()
        table.allJdks.firstOrNull { sdk ->
            sdk.sdkType == javaSdk && sdk.homePath?.let { Path.of(it).normalize() == normalized } == true
        }?.let { return it }
        val base = Path.of(home).fileName?.toString() ?: (spec ?: "jdk")
        var name = "$base (jpm)"
        var i = 2
        while (table.findJdk(name) != null) name = "$base (jpm) ${i++}"
        val sdk = javaSdk.createJdk(name, home, false)
        table.addJdk(sdk)
        return sdk
    }

    private fun languageLevel(release: Int?): LanguageLevel? = release?.let { LanguageLevel.parse(it.toString()) }

    private fun url(p: Path): String = VfsUtilCore.pathToUrl(p.toString())

    private fun rootUrl(p: Path): String =
        if (Files.isDirectory(p)) url(p) else VfsUtilCore.pathToUrl(p.toString()).replaceFirst("file://", "jar://") + "!/"
}
