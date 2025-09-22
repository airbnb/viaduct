package viaduct.gradle

import centralSchemaDirectory
import grtClassesDirectory
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.register
import viaduct.gradle.ViaductPluginCommon.addViaductDependencies
import viaduct.gradle.ViaductPluginCommon.addViaductTestFixtures
import viaduct.gradle.ViaductPluginCommon.applyViaductBOM
import viaduct.gradle.tasks.GenerateViaductCentralSchemaTask
import viaduct.gradle.tasks.GenerateViaductGRTClassFilesTask
import viaduct.graphql.utils.DefaultSchemaProvider

class ViaductApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            require(this == rootProject) {
                "Apply 'com.airbnb.viaduct.application-gradle-plugin' only to the root project."
            }

            val appExt = extensions.create("viaductApplication", ViaductApplicationExtension::class.java, objects)

            // Set default BOM version to plugin version
            appExt.bomVersion.convention(ViaductPluginCommon.BOM.getDefaultVersion())

            plugins.withId("java") {
                if (appExt.applyBOM.get()) {
                    applyViaductBOM(appExt.bomVersion.get())
                    addViaductDependencies(appExt.viaductDependencies.get())
                    addViaductTestFixtures(appExt.viaductTestFixtures.get())
                }
            }

            val generateCentralSchemaTask = generateCentralSchemaTask()
            val generateGRTsTask = generateGRTsTask(
                appExt = appExt,
                centralSchemaDir = centralSchemaDirectory(),
                generateCentralSchemaTask = generateCentralSchemaTask,
            )

            wireGRTClassesIntoClasspath(generateGRTsTask)
        }

    /** Synchronize all modules schema partition's into a single directory. */
    private fun Project.generateCentralSchemaTask(): TaskProvider<GenerateViaductCentralSchemaTask> {
        val allPartitions = configurations.create(ViaductPluginCommon.Configs.ALL_SCHEMA_PARTITIONS_INCOMING).apply {
            description = "Resolvable configuration where all viaduct-module plugins send their schema partitions."
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes { attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.SCHEMA_PARTITION) }
        }

        val generateCentralSchemaTask =
            tasks.register<GenerateViaductCentralSchemaTask>("generateViaductCentralSchema") {
                // Generate the base SDL file as a deterministic output (no project access at execution)
                // We set it up as part of the Sync's work inputs by precomputing content here.
                // The content is stable (no timestamps etc.).
                val precomputedSdl = DefaultSchemaProvider.getSDL()
//            doLast {
//                val baseFile = centralSchemaDir.get().asFile.resolve(BUILTIN_SCHEMA_FILE)
//                val allSchemaFiles = centralSchemaDir.get().asFileTree.matching { include("**/*.graphqls") }.files
//                baseFile.writeText(DefaultSchemaProvider.getDefaultSDL(existingSDLFiles = allSchemaFiles.toList()))
//            }

                schemaPartitions.setFrom(allPartitions.incoming.artifactView {}.files)
                sdl.set(precomputedSdl)
                outputDirectory.set(centralSchemaDirectory())
            }

        configurations.create(ViaductPluginCommon.Configs.CENTRAL_SCHEMA_OUTGOING).apply {
            description = """
              Consumable configuration consisting of a directory containing all schema fragments.  This directory
              is organized as a top-level file named $BUILTIN_SCHEMA_FILE, plus directories named "parition[/module-name]/graphql",
              where module-name is the modulePackageSuffix of the module with dots replaced by slashes (this segment is
              not present if the suffix is blank).
            """.trimIndent()
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes { attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.CENTRAL_SCHEMA) }
            outgoing.artifact(generateCentralSchemaTask)
        }

        return generateCentralSchemaTask
    }

    /** Call the bytecode-generator to generate GRT files. */
    private fun Project.generateGRTsTask(
        appExt: ViaductApplicationExtension,
        centralSchemaDir: Provider<Directory>, // TODO: remove
        generateCentralSchemaTask: TaskProvider<GenerateViaductCentralSchemaTask>,
    ): TaskProvider<Jar> {
        val pluginClasspath = files(ViaductPluginCommon.getClassPathElements(this@ViaductApplicationPlugin::class.java))

        val generateGRTClassesTask = tasks.register<GenerateViaductGRTClassFilesTask>("generateViaductGRTClassFiles") {
            grtClassesDirectory.set(grtClassesDirectory())
            schemaFiles.setFrom(generateCentralSchemaTask.flatMap { it.outputDirectory.map { dir -> dir.asFileTree.matching { include("**/*.graphqls") }.files } })
            grtPackageName.set(appExt.grtPackageName)
            classpath = pluginClasspath
            mainClass.set(CODEGEN_MAIN_CLASS)
        }

        val generateGRTsTask = tasks.register<Jar>("generateViaductGRTs") {
            group = "viaduct"
            description = "Package GRT class files with the central schema."

            archiveBaseName.set("viaduct-grt")
            includeEmptyDirs = false

            dependsOn(generateGRTClassesTask)
            from(grtClassesDirectory())

            // Also include central schema (excluding BUILTIN_SCHEMA_FILE)
            from(centralSchemaDir) {
                into("viaduct/centralSchema")
                exclude(BUILTIN_SCHEMA_FILE)
                includeEmptyDirs = false
            }
        }

        configurations.create(ViaductPluginCommon.Configs.GRT_CLASSES_OUTGOING).apply {
            description =
                "Consumable configuration for the jar file containing the GRT classes plus the central schema's graphqls file."
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.GRT_CLASSES)
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements::class.java, LibraryElements.JAR)
                )
            }
            outgoing.artifact(generateGRTsTask.flatMap { it.archiveFile })
        }

        return generateGRTsTask
    }

    /** Wire the generated GRT classes into the application's own classpath. */
    private fun Project.wireGRTClassesIntoClasspath(generateGRTsTask: TaskProvider<Jar>) {
        dependencies.add("api", files(generateGRTsTask.flatMap { it.archiveFile }).builtBy(generateGRTsTask))
    }

    companion object {
        private const val CODEGEN_MAIN_CLASS = "viaduct.tenant.codegen.cli.SchemaObjectsBytecode\$Main"
        const val BUILTIN_SCHEMA_FILE = "BUILTIN_SCHEMA.graphqls"
    }
}
