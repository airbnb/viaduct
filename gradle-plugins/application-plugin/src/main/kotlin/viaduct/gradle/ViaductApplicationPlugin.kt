package viaduct.gradle

import centralSchemaDirectory
import grtClassesDirectory
import java.io.File
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider
import viaduct.gradle.ViaductPluginCommon.addViaductDependencies
import viaduct.gradle.ViaductPluginCommon.addViaductTestFixtures
import viaduct.gradle.ViaductPluginCommon.applyViaductBOM
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

            val generateCentralSchemaTask = generateCentralSchemaTask(centralSchemaDirectory())
            val generateGRTsTask = generateGRTsTask(
                appExt = appExt,
                centralSchemaDir = centralSchemaDirectory(),
                generateCentralSchemaTask = generateCentralSchemaTask,
            )

            wireGRTClassesIntoClasspath(generateGRTsTask)
        }

    /** Synchronize all modules schema partition's into a single directory. */
    private fun Project.generateCentralSchemaTask(centralSchemaDir: Provider<Directory>): TaskProvider<*> {
        val allPartitions = configurations.create(ViaductPluginCommon.Configs.ALL_SCHEMA_PARTITIONS_INCOMING).apply {
            description = "Resolvable configuration where all viaduct-module plugins send their schema partitions."
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes { attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.SCHEMA_PARTITION) }
        }

        val generateCentralSchemaTask = tasks.register<Sync>("generateViaductCentralSchema") {
            group = "viaduct"
            description = "Collect schema files from all modules into a single directory."

            into(centralSchemaDir)

            // Bring in partitions under a stable prefix
            from(allPartitions.incoming.artifactView {}.files) {
                into("partition")
                include("**/*.graphqls")
            }

            // Generate the base SDL file as a deterministic output (no project access at execution)
            // We set it up as part of the Sync's work inputs by precomputing content here.
            // The content is stable (no timestamps etc.).
            doLast {
                val baseFile = centralSchemaDir.get().asFile.resolve(BUILTIN_SCHEMA_FILE)
                val allSchemaFiles = centralSchemaDir.get().asFileTree.matching { include("**/*.graphqls") }.files
                baseFile.writeText(DefaultSchemaProvider.getDefaultSDL(existingSDLFiles = allSchemaFiles.toList()))
            }
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
            outgoing.artifact(centralSchemaDir) { builtBy(generateCentralSchemaTask) }
        }

        return generateCentralSchemaTask
    }

    /** Command line args for GRT bytecode generation, config-cache friendly. */
    abstract class GrtArgs
        @Inject
        constructor(
            private val objects: org.gradle.api.model.ObjectFactory
        ) : CommandLineArgumentProvider {
            @get:PathSensitive(PathSensitivity.RELATIVE)
            @get:InputFiles
            abstract val schemaFiles: ConfigurableFileCollection

            @get:Input
            abstract val grtPackageName: Property<String>

            @get:OutputDirectory
            abstract val outputDir: DirectoryProperty

            override fun asArguments(): Iterable<String> {
                val schemaCsv = schemaFiles.files.map(File::getAbsolutePath).sorted().joinToString(",")
                return listOf(
                    "--schema_files",
                    schemaCsv,
                    "--pkg_for_generated_classes",
                    grtPackageName.get(),
                    "--generated_directory",
                    outputDir.get().asFile.absolutePath
                )
            }
        }

    /** Call the bytecode-generator to generate GRT files. */
    private fun Project.generateGRTsTask(
        appExt: ViaductApplicationExtension,
        centralSchemaDir: Provider<Directory>,
        generateCentralSchemaTask: TaskProvider<*>,
    ): TaskProvider<Jar> {
        val pluginClasspath = files(ViaductPluginCommon.getClassPathElements(this@ViaductApplicationPlugin::class.java))

        // Build a file collection for all schema files inside the central schema dir (no project access at execution)
        val centralSchemaFiles: Provider<FileTree> =
            centralSchemaDir.map { dir ->
                // Use Directory API to avoid Task.project at execution
                dir.asFileTree.matching { include("**/*.graphqls") }
            }

        val generateGRTClassesTask = tasks.register<JavaExec>("generateViaductGRTClassFiles") {
            // No group: don't want this to appear in task list
            description = "Generate compiled GRT class files from the central schema."

            dependsOn(generateCentralSchemaTask)

            // Inputs/outputs are declared via the args provider as well as outputs below
            outputs.dir(grtClassesDirectory()).withPropertyName("viaductGRTClassesDir")

            classpath = pluginClasspath
            mainClass.set(CODEGEN_MAIN_CLASS)

            // Use a typed, cache-safe argument provider
            argumentProviders.add(
                objects.newInstance(GrtArgs::class.java).apply {
                    // Add the files from the provider without touching project at execution
                    schemaFiles.from(centralSchemaFiles)
                    grtPackageName.set(appExt.grtPackageName)
                    outputDir.set(grtClassesDirectory())
                }
            )
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
            description = "Consumable configuration for the jar file containing the GRT classes plus the central schema's graphqls file."
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.GRT_CLASSES)
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
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
        private const val BUILTIN_SCHEMA_FILE = "BUILTIN_SCHEMA.graphqls"
    }
}
