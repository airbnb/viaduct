package viaduct.gradle

import centralSchemaDirectory
import grtClassesDirectory
import javaGrtClassesDirectory
import javaGrtSourcesDirectory
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.register
import schemaScopingManifestDirectory
import viaduct.apiannotations.InternalApi
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.ViaductPluginCommon.createOrGetCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.createOrGetJavaCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.createOrGetJavaGRTCompileClasspath
import viaduct.gradle.ViaductPluginCommon.pluginVersion
import viaduct.gradle.ViaductPluginCommon.prettyPath
import viaduct.gradle.ViaductPluginCommon.validateApplicationProjectPlacement
import viaduct.gradle.task.AssembleCentralSchemaTask
import viaduct.gradle.task.AssembleSchemaPartitionTask
import viaduct.gradle.task.AssembleTenantModuleConfigFileTask
import viaduct.gradle.task.GenerateExecutionRegistryResourceIndexTask
import viaduct.gradle.task.GenerateGRTClassFilesTask
import viaduct.gradle.task.GenerateJavaGRTSourcesTask
import viaduct.gradle.task.GenerateSchemaResourceIndexTask
import viaduct.gradle.task.GenerateSchemaScopingManifestTask
import viaduct.gradle.task.ValidateSchemaExtensionsTask
import viaduct.scoping.SchemaScopeDefinitions

abstract class ViaductApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            val topology = validateApplicationProjectPlacement()

            extensions.create(
                "viaductApplication",
                ViaductApplicationExtension::class.java,
                objects,
            )

            val viaductModules = setupViaductModulesConfiguration()
            val schemaContributions = setupSchemaContributionsConfiguration(viaductModules)
            val assembleCentralSchemaTask = setupAssembleCentralSchemaTask(viaductModules, schemaContributions)
            setupValidateSchemaExtensionsTask(schemaContributions)
            setupOutgoingConfigurationForCentralSchema(assembleCentralSchemaTask)
            setupIncomingDependenciesFromTopology(topology, viaductModules)

            val schemaScopingManifestTask = setupGenerateSchemaScopingManifestTask()
            val moduleRuntimeClasspath = setupModuleRuntimeClasspathForResourceIndex(viaductModules)
            val schemaResourceIndex = setupGenerateSchemaResourceIndexTask(assembleCentralSchemaTask)
            val executionRegistryResourceIndex = setupGenerateExecutionRegistryResourceIndexTask(
                topology,
                moduleRuntimeClasspath,
            )
            setupExecutionRegistryResourceIndexSource(executionRegistryResourceIndex)
            val kotlinGRTJar = setupKotlinGenerateGRTsTask(
                assembleCentralSchemaTask,
            )
            val schemaResources = setupSchemaResourcesJar(
                assembleCentralSchemaTask,
                schemaScopingManifestTask,
                schemaResourceIndex,
            )
            configurations.matching { it.name == "runtimeOnly" }.configureEach {
                project.dependencies.add(name, files(schemaResources.flatMap { it.archiveFile }))
            }
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
            configurations.matching { it.name == "api" }.configureEach {
                project.dependencies.add("api", files(kotlinGRTJar.flatMap { it.archiveFile }))
            }

            validateJvmPluginApplied()
        }

    /**
     * The "api" and "runtimeOnly" wiring above is deferred until those configurations exist, so it
     * runs regardless of plugin declaration order. But if no Java/Kotlin JVM plugin is ever applied,
     * those configurations never appear and the deferred callbacks silently never fire. Fail loudly
     * at the end of configuration instead of leaving generated GRTs and topology modules quietly
     * missing from the classpath.
     */
    private fun Project.validateJvmPluginApplied() {
        afterEvaluate {
            if (configurations.findByName("api") == null || configurations.findByName("runtimeOnly") == null) {
                throw GradleException(
                    "Project ${prettyPath()} applies 'com.airbnb.viaduct.application-gradle-plugin' but no " +
                        "Java/Kotlin JVM plugin (e.g. 'org.jetbrains.kotlin.jvm' or 'java-library') is applied. " +
                        "Add one to the project's plugins block.",
                )
            }
        }
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
                pluginManager.withPlugin("com.airbnb.viaduct.metamodule-gradle-plugin") {
                    dependencies.add(
                        ViaductPluginCommon.Configs.ALL_SCHEMA_PARTITIONS_INCOMING,
                        files(
                            tasks.named("prepareViaductSchemaPartition", AssembleSchemaPartitionTask::class.java)
                                .flatMap { it.outputDirectory },
                        ),
                    )
                    dependencies.add(
                        ViaductPluginCommon.Configs.ALL_SCHEMA_CONTRIBUTIONS_INCOMING,
                        files(tasks.named("assembleViaductSchemaContributions", Sync::class.java)),
                    )
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

        pluginManager.withPlugin("java") {
            configurations.named("runtimeOnly").configure {
                extendsFrom(viaductModules)
            }
        }

        return viaductModules
    }

    private fun Project.setupValidateSchemaExtensionsTask(schemaContributions: Configuration) {
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
            schemaContributionFiles.setFrom(schemaContributions.incoming.artifactView {}.files)
        }
    }

    private fun Project.setupSchemaContributionsConfiguration(viaductModules: Configuration): Configuration =
        configurations.create(ViaductPluginCommon.Configs.ALL_SCHEMA_CONTRIBUTIONS_INCOMING).apply {
            description = "Schema definitions contributed by Viaduct module-extension plugins."
            isCanBeConsumed = false
            isCanBeResolved = true
            extendsFrom(viaductModules)
            attributes {
                attribute(
                    ViaductPluginCommon.VIADUCT_KIND,
                    ViaductPluginCommon.Kind.SCHEMA_BASE_CONTRIBUTION,
                )
            }
        }

    private fun Project.setupModuleRuntimeClasspathForResourceIndex(viaductModules: Configuration): Configuration =
        configurations.create("viaductModuleRuntimeForResourceIndex").apply {
            description = "Runtime artifacts inspected to index generated Viaduct module configs."
            isCanBeConsumed = false
            isCanBeResolved = true
            extendsFrom(viaductModules)
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements::class.java, LibraryElements.JAR),
                )
            }
        }

    private fun Project.setupGenerateSchemaResourceIndexTask(assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>): TaskProvider<GenerateSchemaResourceIndexTask> =
        tasks.register<GenerateSchemaResourceIndexTask>("generateViaductSchemaResourceIndex") {
            centralSchemaDirectory.set(assembleCentralSchemaTask.flatMap { it.outputDirectory })
            outputDirectory.set(layout.buildDirectory.dir("generated-resources/viaduct-schema-index"))
        }

    private fun Project.setupGenerateExecutionRegistryResourceIndexTask(
        topology: ViaductApplicationTopology,
        moduleRuntimeClasspath: Configuration,
    ): TaskProvider<GenerateExecutionRegistryResourceIndexTask> {
        val task = tasks.register<GenerateExecutionRegistryResourceIndexTask>("generateViaductExecutionRegistryResourceIndex") {
            moduleClasspath.from(moduleRuntimeClasspath.incoming.artifactView {}.files)
            outputDirectory.set(layout.buildDirectory.dir("generated-resources/viaduct-execution-registry-index"))
        }
        if (path in topology.modulePackageSuffixes) {
            listOf(
                "com.airbnb.viaduct.module-gradle-plugin",
                "com.airbnb.viaduct.module-java-gradle-plugin",
            ).forEach { pluginId ->
                pluginManager.withPlugin(pluginId) {
                    task.configure {
                        moduleClasspath.from(
                            tasks.named(
                                "assembleViaductModuleConfigFile",
                                AssembleTenantModuleConfigFileTask::class.java,
                            ).flatMap { it.outputDir }
                        )
                    }
                }
            }
        }
        return task
    }

    private fun Project.setupExecutionRegistryResourceIndexSource(resourceIndex: TaskProvider<GenerateExecutionRegistryResourceIndexTask>) {
        pluginManager.withPlugin("java") {
            extensions.getByType(JavaPluginExtension::class.java).sourceSets.named("main").configure {
                resources.srcDir(resourceIndex.flatMap { it.outputDirectory })
            }
        }
    }

    @OptIn(InternalApi::class)
    private fun Project.setupAssembleCentralSchemaTask(
        viaductModules: Configuration,
        schemaContributions: Configuration,
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
            schemaContributionFiles.setFrom(schemaContributions.incoming.artifactView {}.files)

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

            scopesFile.setFrom(declaredScopesFile())
        }

        return assembleCentralSchemaTask
    }

    @OptIn(InternalApi::class)
    private fun Project.setupGenerateSchemaScopingManifestTask(): TaskProvider<GenerateSchemaScopingManifestTask> =
        tasks.register<GenerateSchemaScopingManifestTask>("generateViaductSchemaScopingManifest") {
            outputDirectory.set(schemaScopingManifestDirectory())

            scopesFile.setFrom(declaredScopesFile())
        }

    @OptIn(InternalApi::class)
    private fun Project.declaredScopesFile() =
        fileTree(ViaductScopesYaml.SOURCE_DIRECTORY) {
            include(SchemaScopeDefinitions.SOURCE_FILE_NAME)
        }

    /** Call the bytecode-generator to generate Kotlin GRT files. */
    private fun Project.setupKotlinGenerateGRTsTask(
        assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>,
    ): TaskProvider<Jar> {
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
            description = "Package compiled Kotlin GraphQL Runtime Type (GRT) classes into a jar."

            archiveBaseName.set("viaduct-grt")
            includeEmptyDirs = false

            from(generateGRTClassesTask.flatMap { it.grtClassesDirectory })
        }

        return generateGRTsTask
    }

    private fun Project.setupSchemaResourcesJar(
        assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>,
        schemaScopingManifestTask: TaskProvider<GenerateSchemaScopingManifestTask>,
        schemaResourceIndex: TaskProvider<GenerateSchemaResourceIndexTask>,
    ): TaskProvider<Jar> =
        tasks.register<Jar>("generateViaductSchemaResources") {
            description = "Package the application schema, resource index, and scope definitions."
            archiveBaseName.set("viaduct-schema")
            includeEmptyDirs = false

            from(assembleCentralSchemaTask.flatMap { it.outputDirectory }) {
                into("viaduct/centralSchema")
                exclude(BUILTIN_SCHEMA_FILE)
                includeEmptyDirs = false
            }

            from(schemaScopingManifestTask.flatMap { it.outputDirectory }) {
                includeEmptyDirs = false
            }
            from(schemaResourceIndex.flatMap { it.outputDirectory })
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

        // Gradle only sets the `javaCompiler` convention on source-set compile tasks, and a manually
        // registered `JavaCompile` falls back to the build JVM rather than `java.toolchain`. Without
        // this, an application that pins a toolchain older than its build JVM gets GRT bytecode that
        // its own `compileJava` cannot read. When no toolchain is declared this resolves to the same
        // build JVM used today, so existing consumers are unaffected.
        pluginManager.withPlugin("java") {
            val toolchain = extensions.getByType(JavaPluginExtension::class.java).toolchain
            val toolchains = extensions.getByType(JavaToolchainService::class.java)
            compileGRTJavaTask.configure {
                javaCompiler.set(toolchains.compilerFor(toolchain))
            }
        }

        val generateGRTsTask = tasks.register<Jar>("generateViaductJavaGRTs") {
            description = "Package compiled Java GraphQL Runtime Type (GRT) classes into a jar."

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
