package viaduct.gradle.federation

import java.util.Properties
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import viaduct.apiannotations.InternalApi
import viaduct.gradle.ViaductApplicationConventions
import viaduct.gradle.ViaductApplicationConventionsPlugin
import viaduct.gradle.registerViaductPlugin

@InternalApi
class FoundationFederationSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.registerViaductPlugin(
            id = "com.airbnb.viaduct.foundation-federation",
            applicationPlugin = FoundationFederationApplicationPlugin::class.java,
        )
    }
}

@InternalApi
class FoundationFederationApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(ViaductApplicationConventionsPlugin::class.java)
        val conventions = project.extensions.getByType(ViaductApplicationConventions::class.java)
        val sdl = project.resources.text.fromUri(requireNotNull(javaClass.getResource("/viaduct/federation/foundation.graphqls")))
        conventions.pluginSchema.into("com.airbnb.viaduct.foundation-federation/source/viaduct/federation") {
            from(sdl) { rename { "foundation.graphqls" } }
        }
        val properties = Properties()
        requireNotNull(javaClass.getResourceAsStream("/viaduct-foundation-version.properties")).use { properties.load(it) }
        val version = requireNotNull(properties.getProperty("version"))
        project.dependencies.add(conventions.schemaValidationPlugins.name, "com.airbnb.viaduct.gradle:federation-validation:$version")
    }
}
