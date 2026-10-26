package viaduct.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import viaduct.apiannotations.StableApi

@StableApi
class ViaductApplicationConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(ViaductApplicationPlugin::class.java)
    }
}
