package viaduct.gradle

import centralSchemaDirectory
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.CopySpec
import org.gradle.api.file.Directory
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.register
import schemaScopingManifestDirectory
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.StableApi
import viaduct.gradle.ViaductApplicationConventionsPlugin.Companion.BUILTIN_SCHEMA_FILE
import viaduct.gradle.ViaductPluginCommon.validateApplicationProjectPlacement
import viaduct.gradle.task.AssembleCentralSchemaTask
import viaduct.gradle.task.AssembleFinalSchemaTask
import viaduct.gradle.task.GenerateExecutionRegistryResourceIndexTask
import viaduct.gradle.task.GenerateSchemaResourceIndexTask
import viaduct.gradle.task.GenerateSchemaScopingManifestTask
import viaduct.gradle.task.ValidateSchemaExtensionsTask
import viaduct.graphql.schema.SchemaProvenance
import viaduct.scoping.SchemaScopeDefinitions

@StableApi
abstract class ViaductApplicationConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            val topology = validateApplicationProjectPlacement()
            val applicationExtension = extensions.create("viaductApplication", ViaductApplicationExtension::class.java, objects)
            val modules = setupViaductModulesConfiguration()
            val contributions = setupSchemaContributionsConfiguration(modules)
            val pluginSchema = copySpec()
            val preparedPluginSchema = tasks.register<Sync>("prepareViaductPluginSchema") {
                into(layout.buildDirectory.dir("viaduct/pluginSchema"))
                into(SchemaProvenance.PLUGIN_DIRECTORY) { with(pluginSchema) }
                includeEmptyDirs = false
                duplicatesStrategy = DuplicatesStrategy.FAIL
                eachFile {
                    require(SchemaProvenance.parse(path)?.scope == "plugin") {
                        "Internal framework/plugin error: invalid plugin SDL destination '$path'. " +
                            "Use <plugin-id>/source/<relative-path> or <plugin-id>/generated/<generator-plugin-id>/<relative-path>."
                    }
                }
            }
            val validationPlugins = configurations.dependencyScope("viaductSchemaValidationPlugins")
            val validationClasspath = configurations.resolvable("viaductSchemaValidationClasspath") {
                extendsFrom(validationPlugins.get())
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
                }
            }
            val schema = setupAssembleCentralSchemaTask(modules, contributions, preparedPluginSchema)
            schema.configure { schemaValidationClasspath.from(validationClasspath) }
            val pluginFinalSchema = copySpec()
            val preparedFinalSchema = tasks.register<Sync>("prepareViaductPluginFinalSchema") {
                into(layout.buildDirectory.dir("viaduct/pluginFinalSchema"))
                into(SchemaProvenance.PLUGIN_DIRECTORY) { with(pluginFinalSchema) }
                includeEmptyDirs = false
                duplicatesStrategy = DuplicatesStrategy.FAIL
                eachFile {
                    require(SchemaProvenance.parse(path)?.scope == "plugin") {
                        "Internal framework/plugin error: invalid final plugin SDL destination '$path'."
                    }
                }
            }
            val finalSchema = tasks.register<AssembleFinalSchemaTask>("assembleViaductFinalSchema") {
                centralSchemaDirectory.set(schema.flatMap { it.outputDirectory })
                pluginSchemaFiles.from(preparedFinalSchema.map { it.destinationDir })
                schemaValidationClasspath.from(validationClasspath)
                scopesFile.from(declaredScopesFile())
                outputDirectory.set(layout.buildDirectory.dir("viaduct/finalSchema"))
            }
            val validation = tasks.register("validateViaductSchema") {
                group = "verification"
                description = "Validate the central and final Viaduct schemas."
                dependsOn(schema, finalSchema)
            }
            tasks.matching { it.name == "check" }.configureEach { dependsOn(validation) }
            setupValidateSchemaExtensionsTask(contributions, preparedPluginSchema)
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
                    pluginSchema = pluginSchema,
                    pluginFinalSchema = pluginFinalSchema,
                    schemaValidationPlugins = configurations.named(validationPlugins.name),
                    finalSchemaDirectory = finalSchema.flatMap { it.outputDirectory },
                    modules = configurations.named(modules.name),
                    allSchemaPartitionsIn = configurations.named(ViaductPluginCommon.Configs.ALL_SCHEMA_PARTITIONS_INCOMING),
                    centralSchema = configurations.named(ViaductPluginCommon.Configs.CENTRAL_SCHEMA_OUTGOING),
                    allModuleManifestsIn = configurations.named(manifests.name),
                    modulePackagePrefix = topology.modulePackagePrefix,
                    centralSchemaDirectory = schema.flatMap { it.outputDirectory },
                ),
            )
            val registryIndex = setupGenerateExecutionRegistryResourceIndexTask(manifests.get())
            setupExecutionRegistryResourceIndexSource(registryIndex)
            val schemaResources = setupSchemaResourcesJar(
                finalSchema.flatMap { it.outputDirectory },
                setupGenerateSchemaScopingManifestTask(),
                setupGenerateSchemaResourceIndexTask(finalSchema.flatMap { it.outputDirectory }),
            )
            configurations.consumable("viaductSchemaResources") {
                attributes {
                    attribute(ViaductArtifacts.KIND, ViaductArtifacts.SCHEMA_RESOURCES)
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, ViaductArtifacts.USAGE))
                }
                outgoing.artifact(schemaResources.flatMap { it.archiveFile })
            }
            configurations.matching { it.name == "runtimeOnly" }.configureEach {
                project.dependencies.add(name, files(schemaResources.flatMap { it.archiveFile }))
            }
        }

    companion object {
        const val BUILTIN_SCHEMA_FILE = SchemaProvenance.BUILTIN_SCHEMA_FILE
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
                configurations.matching { it.name == ViaductPluginCommon.Configs.SCHEMA_PARTITION_OUTGOING }.configureEach {
                    val localPartitions = outgoing.artifacts.files
                    tasks.named("assembleViaductCentralSchema", AssembleCentralSchemaTask::class.java).configure {
                        schemaPartitions.from(localPartitions)
                    }
                }
                configurations.matching { it.name == ViaductPluginCommon.Configs.SCHEMA_CONTRIBUTIONS_OUTGOING }.configureEach {
                    val localContributions = outgoing.artifacts.files
                    tasks.named("assembleViaductCentralSchema", AssembleCentralSchemaTask::class.java).configure {
                        schemaContributionFiles.from(localContributions)
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

    private fun Project.setupValidateSchemaExtensionsTask(
        schemaContributions: Configuration,
        pluginSchema: TaskProvider<Sync>
    ) {
        tasks.register<ValidateSchemaExtensionsTask>("validateViaductSchemaExtensions") {
            pluginSchemaFiles.from(pluginSchema.map { it.destinationDir })
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
            isTransitive = false
            isCanBeConsumed = false
            isCanBeResolved = true
            isCanBeDeclared = false
            extendsFrom(viaductModules)
            attributes {
                attribute(
                    ViaductPluginCommon.VIADUCT_KIND,
                    ViaductPluginCommon.Kind.SCHEMA_BASE_CONTRIBUTION,
                )
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, ViaductArtifacts.USAGE))
            }
        }

    private fun Project.setupGenerateSchemaResourceIndexTask(finalSchema: Provider<Directory>): TaskProvider<GenerateSchemaResourceIndexTask> =
        tasks.register<GenerateSchemaResourceIndexTask>("generateViaductSchemaResourceIndex") {
            centralSchemaDirectory.set(finalSchema)
            outputDirectory.set(layout.buildDirectory.dir("generated-resources/viaduct-schema-index"))
        }

    private fun Project.setupGenerateExecutionRegistryResourceIndexTask(moduleManifests: Configuration): TaskProvider<GenerateExecutionRegistryResourceIndexTask> {
        val task = tasks.register<GenerateExecutionRegistryResourceIndexTask>("generateViaductExecutionRegistryResourceIndex") {
            moduleClasspath.from(moduleManifests)
            outputDirectory.set(layout.buildDirectory.dir("generated-resources/viaduct-execution-registry-index"))
        }
        configurations.matching { it.name == "viaductModuleManifests" }.configureEach {
            val localManifests = outgoing.artifacts.files
            task.configure { moduleClasspath.from(localManifests) }
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
        pluginSchema: TaskProvider<Sync>,
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
            pluginSchemaFiles.from(pluginSchema.map { it.destinationDir })
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
        finalSchema: Provider<Directory>,
        schemaScopingManifestTask: TaskProvider<GenerateSchemaScopingManifestTask>,
        schemaResourceIndex: TaskProvider<GenerateSchemaResourceIndexTask>,
    ): TaskProvider<Jar> =
        tasks.register<Jar>("generateViaductSchemaResources") {
            description = "Package the application schema, resource index, and scope definitions."
            archiveBaseName.set("viaduct-schema")
            includeEmptyDirs = false

            from(finalSchema) {
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
            description = "Central schema retaining artifact-relative schema provenance paths."
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
    override val pluginSchema: CopySpec,
    override val pluginFinalSchema: CopySpec,
    override val schemaValidationPlugins: NamedDomainObjectProvider<Configuration>,
    override val finalSchemaDirectory: Provider<Directory>,
    override val modules: NamedDomainObjectProvider<Configuration>,
    override val allSchemaPartitionsIn: NamedDomainObjectProvider<Configuration>,
    override val centralSchema: NamedDomainObjectProvider<Configuration>,
    override val allModuleManifestsIn: NamedDomainObjectProvider<Configuration>,
    override val modulePackagePrefix: String,
    override val centralSchemaDirectory: Provider<Directory>,
) : ViaductApplicationConventions
