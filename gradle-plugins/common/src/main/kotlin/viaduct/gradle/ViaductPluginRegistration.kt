package viaduct.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.StableApi

@StableApi
fun Settings.registerViaductPlugin(
    id: String,
    applicationPlugin: Class<out Plugin<Project>>,
    moduleStubPlugin: Class<out Plugin<Project>>? = null,
) {
    require(id.isNotBlank()) { "A Viaduct settings plugin ID must not be blank" }
    val registration = ViaductPluginRegistration(applicationPlugin, moduleStubPlugin)
    val previous = viaductPluginRegistry().registrations.putIfAbsent(id, registration)
    if (previous != null && previous != registration) {
        throw GradleException("Conflicting Viaduct plugin registrations for '$id': $previous and $registration")
    }
}

@InternalApi
data class ViaductPluginRegistration(
    val applicationPlugin: Class<out Plugin<Project>>,
    val moduleStubPlugin: Class<out Plugin<Project>>?,
)

@InternalApi
class ViaductPluginRegistry {
    val registrations = linkedMapOf<String, ViaductPluginRegistration>()
}

@InternalApi
fun Settings.viaductPluginRegistry(): ViaductPluginRegistry {
    val name = "viaductPluginRegistrations"
    return extensions.findByType(ViaductPluginRegistry::class.java)
        ?: ViaductPluginRegistry().also { extensions.add(name, it) }
}
