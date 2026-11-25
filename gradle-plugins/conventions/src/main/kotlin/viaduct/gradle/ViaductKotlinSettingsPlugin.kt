package viaduct.gradle

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import viaduct.apiannotations.InternalApi

@InternalApi
class ViaductKotlinSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.registerViaductPlugin(
            id = ViaductGRTLanguage.KOTLIN.settingsPluginId,
            applicationPlugin = ViaductKotlinApplicationPlugin::class.java,
        )
    }
}
