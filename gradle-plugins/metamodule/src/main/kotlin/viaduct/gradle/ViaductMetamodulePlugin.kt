package viaduct.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import viaduct.apiannotations.InternalApi

@InternalApi
class ViaductMetamodulePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(ViaductModuleConventionsPlugin::class.java)
    }

    companion object {
        const val ID = ViaductModuleConventionsPlugin.ID
        const val EXTENSION_NAME = ViaductModuleConventionsPlugin.EXTENSION_NAME
        const val SCHEMA_CONTRIBUTIONS_EXTENSION_NAME = ViaductModuleConventionsPlugin.SCHEMA_CONTRIBUTIONS_EXTENSION_NAME
    }
}
