package viaduct.gradle

import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Usage
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.register
import viaduct.apiannotations.StableApi
import viaduct.gradle.ViaductPluginCommon.validateModuleProjectPlacement

@StableApi
class ViaductModuleConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            val topology = validateModuleProjectPlacement(ID)
            val layout = ViaductModulePluginSupport.modulePackageLayout(this, topology)

            ViaductModulePluginSupport.configureDirectModuleDependencyChecks(this, topology)

            val applicationConfiguration =
                ViaductModulePluginSupport.setupViaductApplicationConfiguration(this)
            val assembleSchemaPartitionTask =
                ViaductModulePluginSupport.setupAssembleSchemaPartitionTask(this, layout)
            ViaductModulePluginSupport.setupOutgoingConfigurationForPartitionSchema(
                this,
                assembleSchemaPartitionTask,
            )
            val centralSchemaConfiguration =
                ViaductModulePluginSupport.setupIncomingConfigurationForCentralSchema(
                    this,
                    applicationConfiguration,
                )
            ViaductModulePluginSupport.wireCentralSchemaToTopologyApplicationProject(
                this,
                topology,
                applicationConfiguration,
                centralSchemaConfiguration,
            )
            setupSchemaResourcesForTests(topology, applicationConfiguration)

            val assembleSchemaContributions = tasks.register<Sync>("assembleViaductSchemaContributions") {
                into(project.layout.buildDirectory.dir("viaduct/schemaContributions"))
                duplicatesStrategy = DuplicatesStrategy.FAIL
            }
            val schemaContributions = DefaultViaductSchemaContributions(assembleSchemaContributions, ViaductModulePluginSupport.schemaModuleId(layout))
            extensions.add(SCHEMA_CONTRIBUTIONS_EXTENSION_NAME, schemaContributions)
            configurations.create(ViaductPluginCommon.Configs.SCHEMA_CONTRIBUTIONS_OUTGOING).apply {
                isCanBeConsumed = true
                isCanBeDeclared = false
                isCanBeResolved = false
                attributes {
                    attribute(
                        ViaductPluginCommon.VIADUCT_KIND,
                        ViaductPluginCommon.Kind.SCHEMA_BASE_CONTRIBUTION,
                    )
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, ViaductArtifacts.USAGE))
                }
                outgoing.artifact(assembleSchemaContributions)
            }

            ViaductGRTSupport.publishModuleRequirements(this)
            val manifests = configurations.consumable("viaductModuleManifests") {
                attributes {
                    attribute(ViaductArtifacts.KIND, ViaductArtifacts.MODULE_MANIFEST)
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, ViaductArtifacts.USAGE))
                }
            }
            extensions.add(
                "viaductModule",
                DefaultViaductModuleConventions(
                    application = configurations.named(applicationConfiguration.name),
                    centralSchemaIn = configurations.named(centralSchemaConfiguration.name),
                    schemaPartition = configurations.named(ViaductPluginCommon.Configs.SCHEMA_PARTITION_OUTGOING),
                    moduleManifests = configurations.named(manifests.name),
                    layout = layout,
                ),
            )
            extensions.add(
                EXTENSION_NAME,
                DefaultViaductMetamoduleExtension(
                    layout = layout,
                    applicationProjectPath = topology.applicationProjectPath,
                    centralSchemaConfiguration = centralSchemaConfiguration,
                ),
            )
        }

    private fun Project.setupSchemaResourcesForTests(
        topology: ViaductApplicationTopology,
        application: Configuration,
    ) {
        val schemaDependencies = configurations.dependencyScope("viaductSchemaResourceDependencies").get()
        val schemaResources = configurations.resolvable("viaductSchemaResourcesIn") {
            extendsFrom(schemaDependencies)
            isTransitive = false
            if (topology.applicationProjectPath != project.path) {
                extendsFrom(application)
                attributes {
                    attribute(ViaductArtifacts.KIND, ViaductArtifacts.SCHEMA_RESOURCES)
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, ViaductArtifacts.USAGE))
                }
            }
        }
        if (topology.applicationProjectPath == path) {
            configurations.matching { it.name == "viaductSchemaResources" }.configureEach {
                val artifacts = outgoing.artifacts.files
                schemaDependencies.dependencies.add(project.dependencies.create(files(artifacts)))
            }
        }
        configurations.matching {
            it.name == "testRuntimeOnly" || it.name == "testFixturesRuntimeOnly"
        }.configureEach {
            project.dependencies.add(name, files(schemaResources))
        }
    }

    companion object {
        const val ID = "com.airbnb.viaduct.metamodule-gradle-plugin"
        const val EXTENSION_NAME = "viaductMetamodule"
        const val SCHEMA_CONTRIBUTIONS_EXTENSION_NAME = "viaductSchemaContributions"
    }
}

internal data class DefaultViaductModuleConventions(
    override val application: NamedDomainObjectProvider<Configuration>,
    override val centralSchemaIn: NamedDomainObjectProvider<Configuration>,
    override val schemaPartition: NamedDomainObjectProvider<Configuration>,
    override val moduleManifests: NamedDomainObjectProvider<Configuration>,
    override val layout: ViaductModulePackageLayout,
) : ViaductModuleConventions
