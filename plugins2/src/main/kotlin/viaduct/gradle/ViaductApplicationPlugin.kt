package viaduct.gradle

import centralSchemaDirectory
import centralSchemaDirectoryName
import grtClassesDirectory
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.language.jvm.tasks.ProcessResources

open class ViaductApplicationExtension(objects: org.gradle.api.model.ObjectFactory) {
    /** Kotlin package name for generated GRT classes. */
    val grtPackageName = objects.property(String::class.java).convention("viaduct.api.grts")
    
    /** Kotlin package name prefix for all modules. */
    val modulePackagePrefix = objects.property(String::class.java)
}

class ViaductApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        require(this == rootProject) {
            // TODO -- relax this restriction someday, want to work well in a monorepo
            "Apply 'viaduct-application' only to the root project."
        }

        val appExt = extensions.create("viaductApplication", ViaductApplicationExtension::class.java, objects)

        val generateCentralSchemaTask = generateCentralSchemaTask(centralSchemaDirectory())
        generateGRTsTask(
            appExt,
            centralSchemaDirectory(),
            generateCentralSchemaTask,
        )
    }

    /** Synchronize all modules schema partition's into a single directory. */
    private fun Project.generateCentralSchemaTask(centralSchemaDir: Provider<Directory>): TaskProvider<*> {
        // Resolvable config that will collect all modules’ schema partitions
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
            from(allPartitions.incoming.artifactView {}.files) {
                into("partition") // extra prefix to support the empty module name
                include("**/*.graphqls")
            }

            doLast {
                val baseFile = centralSchemaDir.get().asFile.resolve(BASE_SCHEMA_FILE)
                baseFile.writeText(BASE_SCHEMA_CONTENT)
            }
        }

        // Publish the central schema as a consumable artifact
        // Intended for viaduct-module projects and other projects to cleanly consumer the central schema
        // Since the generateGRTs task is internal to the generateCentralSchema project, it uses centralSchemaDir directly
        configurations.create(ViaductPluginCommon.Configs.CENTRAL_SCHEMA_OUTGOING).apply {
            description = """
              Consumable configuration consisting of a directory containing all schema fragments.  This directory
              is organized as a top-level file named $BASE_SCHEMA_FILE, plus directories named "parition[/module-name]/graphql",
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

    /** Call the bytecode-generator to generate GRT files. */
    private fun Project.generateGRTsTask(
        appExt: ViaductApplicationExtension,
        centralSchemaDir: Provider<Directory>,
        generateCentralSchemaTask: TaskProvider<*>,
    ) : TaskProvider<*> {
        // Build a FileCollection from the plugin's classloader URLs (includes plugin impl deps like :tenant:codegen)
        val pluginClasspath = files(ViaductPluginCommon.getClassPathElements(this@ViaductApplicationPlugin::class.java))

        val generateGRTClassesTask = tasks.register<JavaExec>("generateViaductGRTClassFiles") {
            // Make sure central schema exists first
            dependsOn(generateCentralSchemaTask) // TODO - I think we can remove if we have a dedicated task

            inputs.dir(centralSchemaDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("viaductCentralSchemaDir")
            outputs.dir(grtClassesDirectory()).withPropertyName("viaductGRTClassesDir")

            // Use the plugin's classpath (contains :tenant:codegen and its deps)
            classpath = pluginClasspath
            mainClass.set(CODEGEN_MAIN_CLASS)

            doFirst {
                val csFiles = project.fileTree(centralSchemaDir).files
                val centralSchemaFilePaths = csFiles.map { it.absolutePath }.sorted().joinToString(",")
                val pkg = appExt.grtPackageName.get()
                val grtClassesDirPath = grtClassesDirectory().get().asFile.apply { mkdirs() }.absolutePath

                args(
                    "--schema_files", centralSchemaFilePaths,
                    "--pkg_for_generated_classes", pkg,
                    "--generated_directory", grtClassesDirPath
                )
            }
        }

        val generateGRTsTask = tasks.register<Jar>("generateViaductGRTs") {
            group = "viaduct"
            description = "Generate compiled GRT class files from the central schema."

            archiveBaseName.set("viaduct-grt")
            includeEmptyDirs = false

            dependsOn(generateGRTClassesTask) // TODO - I think we can remove if we have a dedicated task
            from(grtClassesDir) // class files

            dependsOn(generateCentralSchemaTask) // TODO - I think we can remove if we have a dedicated task
            from(centralSchemaDir) { // central schema is in GRT file (for now) - supports testing use case
                into("viaduct/centralSchema")
                includeEmptyDirs = false
                // TODO based on Skevy's PR: exclude("**/$BASE_SCHEMA_FILE")
            }
        }

        // Publish the generated GRT classes as a consumable artifact
        configurations.create(ViaductPluginCommon.Configs.GRT_CLASSES_OUTGOING).apply {
            description = "Consumable configuration for the jar file containing the GRT classes plus the central schema's graphqls file."

            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.GRT_CLASSES)

                // These will make us more friendly to IDEs and other tools
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
            }
            outgoing.artifact(generateGRTsTask.flatMap { it.archiveFile })
        }

        return generateGRTsTask
    }

    companion object {
        private val CODEGEN_MAIN_CLASS = "viaduct.tenant.codegen.cli.SchemaObjectsBytecode\$Main"

        private val BASE_SCHEMA_FILE = "BASE_SCHEMA.graphqls"

        private val BASE_SCHEMA_CONTENT = """
           # --- Viaduct Base Schema (built in) ---
           # These schema elements are always built into Viaduct application schemas
           # --- End Viaduct Base Schema ---

           """.trimIndent()
    }
}
