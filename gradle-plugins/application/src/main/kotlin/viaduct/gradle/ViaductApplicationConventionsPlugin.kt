package viaduct.gradle

import centralSchemaDirectory
import grtClassesDirectory
import javaGrtClassesDirectory
import javaGrtSourcesDirectory
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import viaduct.apiannotations.StableApi
import viaduct.gradle.ViaductApplicationPlugin.Companion.BUILTIN_SCHEMA_FILE
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.Directory
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

@StableApi
abstract class ViaductApplicationConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        val topology = validateApplicationProjectPlacement()
        val applicationExtension = extensions.create("viaductApplication", ViaductApplicationExtension::class.java, objects)
        val modules = setupViaductModulesConfiguration()
        val contributions = setupSchemaContributionsConfiguration(modules)
        val schema = setupAssembleCentralSchemaTask(modules, contributions)
        setupValidateSchemaExtensionsTask(contributions)
        setupOutgoingConfigurationForCentralSchema(schema)
        setupIncomingDependenciesFromTopology(topology, modules)
        val manifests = configurations.resolvable("viaductAllModuleManifestsIn") {
            extendsFrom(modules)
            isTransitive = false
            attributes {
                attribute(ViaductArtifacts.KIND, ViaductArtifacts.MODULE_MANIFEST)
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, ViaductArtifacts.USAGE))
            }
        }
        applicationExtension.initializeConventions(
            DefaultViaductApplicationConventions(
                modules = configurations.named(modules.name),
                allSchemaPartitionsIn = configurations.named(ViaductPluginCommon.Configs.ALL_SCHEMA_PARTITIONS_INCOMING),
                centralSchema = configurations.named(ViaductPluginCommon.Configs.CENTRAL_SCHEMA_OUTGOING),
                allModuleManifestsIn = configurations.named(manifests.name),
                modulePackagePrefix = topology.modulePackagePrefix,
                centralSchemaDirectory = schema.flatMap { it.outputDirectory },
            ),
        )
        val runtime = setupModuleRuntimeClasspathForResourceIndex(modules)
        val registryIndex = setupGenerateExecutionRegistryResourceIndexTask(topology, runtime)
        setupExecutionRegistryResourceIndexSource(registryIndex)
        val schemaResources = setupSchemaResourcesJar(
            schema,
            setupGenerateSchemaScopingManifestTask(),
            setupGenerateSchemaResourceIndexTask(schema),
        )
        configurations.matching { it.name == "runtimeOnly" }.configureEach {
            project.dependencies.add(name, files(schemaResources.flatMap { it.archiveFile }))
        }
        val applicationPath = path
        val sharedLibraries = runtime.incoming.artifactView {
            componentFilter { component ->
                component is ProjectComponentIdentifier && component.projectPath == applicationPath
            }
        }.files
        configurations.matching { it.name == "api" }.configureEach {
            project.dependencies.add(name, files(sharedLibraries))
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
                    tasks.named("assembleViaductCentralSchema", AssembleCentralSchemaTask::class.java).configure {
                        schemaPartitions.from(
                            tasks.named("prepareViaductSchemaPartition", AssembleSchemaPartitionTask::class.java)
                                .flatMap { it.outputDirectory },
                        )
                        schemaContributionFiles.from(files(tasks.named("assembleViaductSchemaContributions", Sync::class.java)))
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
            isCanBeDeclared = false
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
            isCanBeDeclared = false
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
            isCanBeDeclared = false
            isTransitive = false
            extendsFrom(viaductModules)
            attributes {
                attribute(ViaductArtifacts.KIND, ViaductArtifacts.SCHEMA_PARTITION)
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, ViaductArtifacts.USAGE))
            }
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

    private fun Project.setupOutgoingConfigurationForCentralSchema(assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>) {
        configurations.create(ViaductPluginCommon.Configs.CENTRAL_SCHEMA_OUTGOING).apply {
            description = """
              Consumable configuration consisting of a directory containing all schema fragments.  This directory
              is organized as a top-level file named $BUILTIN_SCHEMA_FILE, plus directories named "parition[/module-name]/graphql",
              where module-name is the modulePackageSuffix of the module with dots replaced by slashes (this segment is
              not present if the suffix is blank).
            """.trimIndent()
            isCanBeConsumed = true
            isCanBeDeclared = false
            isCanBeResolved = false
            attributes {
                attribute(ViaductArtifacts.KIND, ViaductArtifacts.CENTRAL_SCHEMA)
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, ViaductArtifacts.USAGE))
            }
            outgoing.artifact(assembleCentralSchemaTask)
        }
    }
}

internal data class DefaultViaductApplicationConventions(
    override val modules: NamedDomainObjectProvider<Configuration>,
    override val allSchemaPartitionsIn: NamedDomainObjectProvider<Configuration>,
    override val centralSchema: NamedDomainObjectProvider<Configuration>,
    override val allModuleManifestsIn: NamedDomainObjectProvider<Configuration>,
    override val modulePackagePrefix: String,
    override val centralSchemaDirectory: Provider<Directory>,
) : ViaductApplicationConventions
