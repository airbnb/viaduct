package viaduct.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
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
    }
}
