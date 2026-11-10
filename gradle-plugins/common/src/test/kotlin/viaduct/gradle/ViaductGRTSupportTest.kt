package viaduct.gradle

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.bundling.Jar
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class ViaductGRTSupportTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `central schema root comes from the file tree rather than provenance-like checkout names`() {
        val project = project("schema-root")
        val root = directory.resolve("schema/plugin/checkout/source/build/central")
        root.resolve("schema/module/catalog.pricing/source/nested/types.graphqls").apply {
            parentFile.mkdirs()
            writeText("type Product { name: String }")
        }
        val files = project.files(project.fileTree(root))

        assertEquals(root, ViaductPluginCommon.schemaArtifactRoot(files))
    }

    @TestFactory
    fun `both languages publish API and runtime JAR variants`() =
        ViaductGRTLanguage.values().map { language ->
            DynamicTest.dynamicTest(language.name) {
                val project = project("variants-${language.name}")
                project.pluginManager.apply(ApplicationPlugin::class.java)
                mapOf("Api" to Usage.JAVA_API, "Runtime" to Usage.JAVA_RUNTIME).forEach { (suffix, usage) ->
                    val variant = project.configurations.getByName("viaduct${language.configurationPrefix}Grt${suffix}Elements")
                    assertEquals(usage, variant.attributes.getAttribute(Usage.USAGE_ATTRIBUTE)?.name)
                    assertEquals(Category.LIBRARY, variant.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name)
                    assertEquals(LibraryElements.JAR, variant.attributes.getAttribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE)?.name)
                    assertEquals(language.capability, variant.outgoing.capabilities.single().let { "${it.group}:${it.name}" })
                    assertEquals("test-version", variant.outgoing.capabilities.single().version)
                    assertEquals(jar(project, language).archiveFile.get().asFile, variant.outgoing.artifacts.single().file)
                    assertTrue(variant.dependencies.isEmpty())
                }
            }
        }

    @TestFactory
    fun `local GRT dependencies work in either plugin order`() =
        ViaductGRTLanguage.values().flatMap { language ->
            listOf(true, false).map { applicationFirst ->
                DynamicTest.dynamicTest("${language.name}, applicationFirst=$applicationFirst") {
                    val project = project("local-${language.name}-$applicationFirst")
                    val application = project.configurations.dependencyScope("viaductApplication")
                    if (applicationFirst) project.pluginManager.apply(ApplicationPlugin::class.java)
                    ViaductGRTSupport.setupModuleDependencies(project, application, language, ApplicationPlugin::class.java)
                    if (!applicationFirst) project.pluginManager.apply(ApplicationPlugin::class.java)
                    project.pluginManager.apply("java-test-fixtures")

                    val bucket = project.configurations.getByName("viaduct${language.configurationPrefix}GrtDependencies")
                    val dependency = bucket.dependencies.single() as FileCollectionDependency
                    assertEquals(setOf(jar(project, language).archiveFile.get().asFile), dependency.files.files)
                    assertEquals(setOf(jar(project, language)), dependency.files.buildDependencies.getDependencies(null))
                    assertSourceSets(project, language)
                }
            }
        }

    @TestFactory
    fun `cross-project GRT dependencies preserve the application dependency and select one capability`() =
        ViaductGRTLanguage.values().map { language ->
            DynamicTest.dynamicTest(language.name) {
                val applicationProject = project("cross-${language.name}")
                val module = ProjectBuilder.builder().withName("module").withParent(applicationProject).build()
                val application = module.configurations.dependencyScope("viaductApplication")
                val original = (module.dependencies.project(mapOf("path" to applicationProject.path)) as ProjectDependency).apply {
                    isTransitive = false
                    exclude(mapOf("group" to "example.excluded"))
                }
                application.get().dependencies.add(original)
                ViaductGRTSupport.setupModuleDependencies(module, application, language, ApplicationPlugin::class.java)
                module.pluginManager.apply("java-library")
                module.pluginManager.apply("java-test-fixtures")

                val bucket = module.configurations.getByName("viaduct${language.configurationPrefix}GrtDependencies")
                val selected = bucket.dependencies.single() as ProjectDependency
                assertEquals(original.path, selected.path)
                assertEquals(original.isTransitive, selected.isTransitive)
                assertEquals(original.excludeRules, selected.excludeRules)
                assertNull(selected.targetConfiguration)
                assertEquals(language.capability, selected.requestedCapabilities.single().let { "${it.group}:${it.name}" })
                assertTrue(original.requestedCapabilities.isEmpty())
                assertSourceSets(module, language)
            }
        }

    @Test
    fun `module requirement metadata is artifactless and independent of runtime dependencies`() {
        val project = project("requirements")
        ViaductGRTSupport.publishModuleRequirements(project)
        val application = project.configurations.dependencyScope("viaductApplication")
        ViaductGRTLanguage.values().forEach { language ->
            ViaductGRTSupport.setupModuleDependencies(project, application, language, ApplicationPlugin::class.java)
        }
        project.pluginManager.apply(ApplicationPlugin::class.java)
        project.dependencies.add("runtimeOnly", "unavailable.example:runtime:1")

        val variant = project.configurations.getByName("viaductModuleGRTRequirements")
        assertEquals("JAVA,KOTLIN", variant.attributes.getAttribute(Attribute.of("viaduct.grt.languages", String::class.java)))
        assertTrue(variant.allDependencies.isEmpty())
        assertTrue(variant.outgoing.artifacts.isEmpty())
    }

    @TestFactory
    fun `application requirement resolution is nontransitive and unused generators have no task dependency`() =
        ViaductGRTLanguage.values().map { language ->
            DynamicTest.dynamicTest(language.name) {
                val project = project("unused-${language.name}")
                project.pluginManager.apply(ApplicationPlugin::class.java)
                val modules = project.configurations.dependencyScope("viaductModules")
                ViaductGRTSupport.setupApplicationDependencies(project, modules, language, jar(project, language).archiveFile)

                val incoming = project.configurations.getByName("viaduct${language.configurationPrefix}GRTRequirementsIn")
                assertEquals(false, incoming.isTransitive)
                assertEquals(setOf(modules.get()), incoming.extendsFrom)
                val dependency = project.configurations.getByName("api").dependencies.single() as FileCollectionDependency
                assertTrue(dependency.files.files.isEmpty())
                assertTrue(dependency.files.buildDependencies.getDependencies(null).isEmpty())
            }
        }

    @TestFactory
    fun `application selects the local participating language generator`() =
        ViaductGRTLanguage.values().map { language ->
            DynamicTest.dynamicTest(language.name) {
                val project = project("participating-${language.name}")
                project.pluginManager.apply(ApplicationPlugin::class.java)
                val modules = project.configurations.dependencyScope("viaductModules")
                ViaductGRTLanguage.values().forEach { candidate ->
                    ViaductGRTSupport.setupApplicationDependencies(project, modules, candidate, jar(project, candidate).archiveFile)
                }
                ViaductGRTSupport.setupModuleDependencies(
                    project,
                    project.configurations.dependencyScope("viaductApplication"),
                    language,
                    ApplicationPlugin::class.java,
                )

                val files = project.configurations.getByName("api").dependencies.withType(FileCollectionDependency::class.java)
                assertEquals(setOf(jar(project, language)), files.flatMap { it.files.buildDependencies.getDependencies(null) }.toSet())
            }
        }

    private fun project(name: String): Project =
        ProjectBuilder.builder()
            .withName(name)
            .withProjectDir(File(directory, name).apply { mkdirs() })
            .build()

    private fun jar(
        project: Project,
        language: ViaductGRTLanguage
    ) = project.tasks.named(language.jarTaskName, Jar::class.java).get()

    private fun assertSourceSets(
        project: Project,
        language: ViaductGRTLanguage
    ) {
        val bucket = project.configurations.getByName("viaduct${language.configurationPrefix}GrtDependencies")
        listOf("implementation", "testImplementation", "testFixturesImplementation").forEach { name ->
            assertTrue(bucket in project.configurations.getByName(name).extendsFrom, name)
        }
        val incoming = project.configurations.getByName("viaduct${language.configurationPrefix}GRTClassesIn")
        assertTrue(bucket in incoming.extendsFrom)
        assertEquals(Usage.JAVA_API, incoming.attributes.getAttribute(Usage.USAGE_ATTRIBUTE)?.name)
        assertEquals(Category.LIBRARY, incoming.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name)
        assertEquals(LibraryElements.JAR, incoming.attributes.getAttribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE)?.name)
    }

    class ApplicationPlugin : Plugin<Project> {
        override fun apply(project: Project) {
            project.pluginManager.apply("java-library")
            ViaductGRTLanguage.values().forEach { language ->
                val jar = project.tasks.register(language.jarTaskName, Jar::class.java) { task ->
                    task.archiveBaseName.set(language.name.lowercase())
                }
                ViaductGRTSupport.publishVariants(project, language, jar.flatMap { it.archiveFile }, "test-version")
            }
        }
    }
}
