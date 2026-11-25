package viaduct.gradle

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import viaduct.apiannotations.InternalApi

@InternalApi
class ViaductJavaSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.registerViaductPlugin(
            id = ViaductGRTLanguage.JAVA.settingsPluginId,
            applicationPlugin = ViaductJavaApplicationPlugin::class.java,
        )
    }
}
