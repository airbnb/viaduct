package viaduct.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.register
import viaduct.apiannotations.InternalApi
import viaduct.gradle.ViaductPluginCommon.validateModuleProjectPlacement

@InternalApi
class ViaductMetamodulePlugin : Plugin<Project> {
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

            val assembleSchemaContributions = tasks.register<Sync>("assembleViaductSchemaContributions") {
                into(project.layout.buildDirectory.dir("viaduct/schemaContributions"))
            }
            val schemaContributions = DefaultViaductSchemaContributions(assembleSchemaContributions)
            extensions.add(SCHEMA_CONTRIBUTIONS_EXTENSION_NAME, schemaContributions)
            configurations.create(ViaductPluginCommon.Configs.SCHEMA_CONTRIBUTIONS_OUTGOING).apply {
                isCanBeConsumed = true
                isCanBeResolved = false
                attributes {
                    attribute(
                        ViaductPluginCommon.VIADUCT_KIND,
                        ViaductPluginCommon.Kind.SCHEMA_BASE_CONTRIBUTION,
                    )
                }
                outgoing.artifact(assembleSchemaContributions)
            }

            extensions.add(
                EXTENSION_NAME,
                DefaultViaductMetamoduleExtension(
                    layout = layout,
                    applicationProjectPath = topology.applicationProjectPath,
                    centralSchemaConfiguration = centralSchemaConfiguration,
                ),
            )
        }

    companion object {
        const val ID = "com.airbnb.viaduct.metamodule-gradle-plugin"
        const val EXTENSION_NAME = "viaductMetamodule"
        const val SCHEMA_CONTRIBUTIONS_EXTENSION_NAME = "viaductSchemaContributions"
    }
}
