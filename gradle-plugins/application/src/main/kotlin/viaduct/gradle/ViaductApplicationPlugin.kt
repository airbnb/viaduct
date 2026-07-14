package viaduct.gradle

import centralSchemaDirectory
import grtClassesDirectory
import javaGrtClassesDirectory
import javaGrtSourcesDirectory
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.register
import viaduct.apiannotations.ExperimentalApi
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.ViaductPluginCommon.createOrGetCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.createOrGetJavaCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.createOrGetJavaGRTCompileClasspath
import viaduct.gradle.ViaductPluginCommon.pluginVersion
import viaduct.gradle.ViaductPluginCommon.validateApplicationProjectPlacement
import viaduct.gradle.task.AssembleCentralSchemaTask
import viaduct.gradle.task.AssembleSchemaPartitionTask
import viaduct.gradle.task.GenerateGRTClassFilesTask
import viaduct.gradle.task.GenerateJavaGRTSourcesTask
import viaduct.gradle.task.ValidateSchemaExtensionsTask

abstract class ViaductApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            val topology = validateApplicationProjectPlacement()

            val appExt = extensions.create(
                "viaductApplication",
                ViaductApplicationExtension::class.java,
                objects,
            )

            val viaductModules = setupViaductModulesConfiguration()
            val assembleCentralSchemaTask = setupAssembleCentralSchemaTask(viaductModules, appExt)
            setupValidateSchemaExtensionsTask(appExt)
            setupOutgoingConfigurationForCentralSchema(assembleCentralSchemaTask)
            setupIncomingDependenciesFromTopology(topology, viaductModules)

            val kotlinGRTJar = setupKotlinGenerateGRTsTask(assembleCentralSchemaTask)
            val javaGRTJar = setupJavaGenerateGRTsTask(assembleCentralSchemaTask)
            extensions.add(
                VIADUCT_APPLICATION_OUTPUTS_EXTENSION_NAME,
                ViaductApplicationOutputProviders(
                    centralSchemaDirectory = assembleCentralSchemaTask.flatMap { it.outputDirectory },
                    kotlinGrtJar = kotlinGRTJar.flatMap { it.archiveFile },
                    javaGrtJar = javaGRTJar.flatMap { it.archiveFile },
                ),
            )

            configureIdeaIntegration(kotlinGRTJar)

            setupConsumableConfigurationForGRT(
                ViaductPluginCommon.Configs.GRT_CLASSES_KOTLIN_OUTGOING,
                ViaductPluginCommon.Kind.KOTLIN_GRT_CLASSES,
                kotlinGRTJar.flatMap { it.archiveFile },
            )
            setupConsumableConfigurationForGRT(
                ViaductPluginCommon.Configs.GRT_CLASSES_JAVA_OUTGOING,
                ViaductPluginCommon.Kind.JAVA_GRT_CLASSES,
                javaGRTJar.flatMap { it.archiveFile },
            )

            // Expose the Kotlin GRT jar on the application project's own classpath so local sources
            // (main + tests) can reference generated types. Java-specific application projects can
            // depend on generateViaductJavaGRTs explicitly.
            this.dependencies.add("api", files(kotlinGRTJar.flatMap { it.archiveFile }))
        }

    private fun Project.setupIncomingDependenciesFromTopology(
        topology: ViaductApplicationTopology,
        viaductModules: Configuration,
    ) {
        topology.modulePackageSuffixes.keys.forEach { modulePath ->
            if (modulePath != path) {
                dependencies.add(
                    viaductModules.name,
                    dependencies.project(mapOf("path" to modulePath)),
                )
            } else {
                listOf(
                    "com.airbnb.viaduct.module-gradle-plugin",
                    "com.airbnb.viaduct.module-java-gradle-plugin",
                ).forEach { pluginId ->
                    pluginManager.withPlugin(pluginId) {
                        dependencies.add(
                            ViaductPluginCommon.Configs.ALL_SCHEMA_PARTITIONS_INCOMING,
                            files(
                                tasks.named("prepareViaductSchemaPartition", AssembleSchemaPartitionTask::class.java)
                                    .flatMap { it.outputDirectory },
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun Project.setupViaductModulesConfiguration(): Configuration {
        val viaductModules = configurations.create(ViaductPluginCommon.Configs.VIADUCT_MODULES).apply {
            description = "Dependency bucket for Viaduct module projects used by this application."
            isCanBeConsumed = false
            isCanBeResolved = false
        }

        configurations.named("runtimeOnly").configure {
            extendsFrom(viaductModules)
        }

        return viaductModules
    }

    @OptIn(ExperimentalApi::class)
    private fun Project.setupValidateSchemaExtensionsTask(appExt: ViaductApplicationExtension) {
        tasks.register<ValidateSchemaExtensionsTask>("validateViaductSchemaExtensions") {
            baseSchemaFiles.setFrom(
                project.fileTree("src/main/viaduct/schemabase") {
                    include("**/*.graphqls")
                }
            )
            commonSchemaFiles.setFrom(
                project.fileTree("src/viaduct/schema") {
                    include("**/*.graphqls")
                }
            )
            // Same input as AssembleCentralSchemaTask so preflight and assembly agree on whether
            // `@scope` is defined in the framework's built-in SDL.
            schemaScoping.set(appExt.schemaScoping)
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun Project.setupAssembleCentralSchemaTask(
        viaductModules: Configuration,
        appExt: ViaductApplicationExtension,
    ): TaskProvider<AssembleCentralSchemaTask> {
        val allPartitions = configurations.create(ViaductPluginCommon.Configs.ALL_SCHEMA_PARTITIONS_INCOMING).apply {
            description = "Resolvable configuration where all viaduct-module plugins send their schema partitions."
            isCanBeConsumed = false
            isCanBeResolved = true
            extendsFrom(viaductModules)
            attributes { attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.SCHEMA_PARTITION) }
        }

        val assembleCentralSchemaTask = tasks.register<AssembleCentralSchemaTask>("assembleViaductCentralSchema") {
            schemaPartitions.setFrom(allPartitions.incoming.artifactView {}.files)

            baseSchemaFiles.setFrom(
                project.fileTree("src/main/viaduct/schemabase") {
                    include("**/*.graphqls")
                }
            )

            // Root types schema files: global Query/Mutation/Subscription extensions
            // for the entire project (not module-specific)
            commonSchemaFiles.setFrom(
                project.fileTree("src/viaduct/schema") {
                    include("**/*.graphqls")
                }
            )

            outputDirectory.set(centralSchemaDirectory())

            // Pipe the full SchemaScoping through the task input. Slice 3 only reads
            // `schemaScoping.get().scopeUniverse` here, but the full shape is what downstream
            // slices need on the same task surface (slice 5 reads `scopedSchemas` for
            // materialization; slice 8 serializes both fields to the JSON manifest). Binding the
            // full shape now avoids an in-flight task-input migration in a follow-up PR.
            schemaScoping.set(appExt.schemaScoping)
        }

        return assembleCentralSchemaTask
    }

    /** Call the bytecode-generator to generate Kotlin GRT files. */
    private fun Project.setupKotlinGenerateGRTsTask(assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>): TaskProvider<Jar> {
        val version = pluginVersion(ViaductApplicationPlugin::class.java)
        val codegenClasspath = createOrGetCodegenClasspath(version)

        val generateGRTClassesTask = tasks.register<GenerateGRTClassFilesTask>("generateViaductGRTClassFiles") {
            buildFlags.putAll(ViaductPluginCommon.DEFAULT_BUILD_FLAGS)
            grtClassesDirectory.set(grtClassesDirectory())
            schemaFiles.setFrom(assembleCentralSchemaTask.flatMap { it.outputDirectory.map { dir -> dir.asFileTree.matching { include("**/*.graphqls") }.files } })
            classpath.setFrom(codegenClasspath)
            mainClass.set(CODEGEN_MAIN_CLASS)
        }

        val generateGRTsTask = tasks.register<Jar>("generateViaductGRTs") {
            description = "Package compiled GraphQL Runtime Type (GRT) classes and the merged schema into a jar. This jar is added to your compile classpath automatically."

            archiveBaseName.set("viaduct-grt")
            includeEmptyDirs = false

            from(generateGRTClassesTask.flatMap { it.grtClassesDirectory })

            from(assembleCentralSchemaTask.flatMap { it.outputDirectory }) {
                into("viaduct/centralSchema")
                exclude(BUILTIN_SCHEMA_FILE)
                includeEmptyDirs = false
            }
        }

        return generateGRTsTask
    }

    /**
     * Generate Java GRT source files from the central schema, compile them with javac,
     * and package the resulting classes with the central schema into a Jar.
     */
    private fun Project.setupJavaGenerateGRTsTask(assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>): TaskProvider<Jar> {
        val version = pluginVersion(ViaductApplicationPlugin::class.java)
        val codegenClasspath = createOrGetJavaCodegenClasspath(version)
        val grtCompileClasspath = createOrGetJavaGRTCompileClasspath(version)

        val generateGRTSourcesTask = tasks.register<GenerateJavaGRTSourcesTask>("generateViaductJavaGRTSources") {
            grtSourcesDirectory.set(javaGrtSourcesDirectory())
            schemaFiles.setFrom(
                assembleCentralSchemaTask.flatMap { it.outputDirectory.map { dir -> dir.asFileTree.matching { include("**/*.graphqls") }.files } }
            )
            classpath.setFrom(codegenClasspath)
        }

        val compileGRTJavaTask = tasks.register<JavaCompile>("compileViaductJavaGRTJava") {
            dependsOn(generateGRTSourcesTask)
            source = fileTree(generateGRTSourcesTask.flatMap { it.grtSourcesDirectory })
            destinationDirectory.set(javaGrtClassesDirectory())
            classpath = grtCompileClasspath
            options.isIncremental = true
        }

        val generateGRTsTask = tasks.register<Jar>("generateViaductJavaGRTs") {
            description = "Package compiled Java GraphQL Runtime Type (GRT) classes into a jar. The schema is already bundled in the Kotlin GRT jar."

            archiveBaseName.set("viaduct-java-grt")
            includeEmptyDirs = false

            from(compileGRTJavaTask.map { it.destinationDirectory })
        }

        return generateGRTsTask
    }

    private fun Project.setupOutgoingConfigurationForCentralSchema(assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>) {
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
            outgoing.artifact(assembleCentralSchemaTask)
        }
    }

    private fun Project.setupConsumableConfigurationForGRT(
        configName: String,
        kind: String,
        artifact: Provider<RegularFile>
    ) {
        configurations.create(configName).apply {
            description =
                "Consumable configuration for the jar file containing the GRT classes plus the central schema's graphqls file."
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(ViaductPluginCommon.VIADUCT_KIND, kind)
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements::class.java, LibraryElements.JAR)
                )
            }
            outgoing.artifact(artifact)
        }
    }

    companion object {
        private const val CODEGEN_MAIN_CLASS = "viaduct.tenant.codegen.cli.SchemaObjectsBytecode\$Main"
        const val BUILTIN_SCHEMA_FILE = "BUILTIN_SCHEMA.graphqls"
    }
}
