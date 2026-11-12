package viaduct.gradle.apollo

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.kotlin.dsl.register
import viaduct.gradle.ViaductApplicationConventions
import viaduct.gradle.ViaductApplicationConventionsPlugin
import viaduct.gradle.registerViaductPlugin

class ApolloSchemaSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.registerViaductPlugin(PLUGIN_ID, ApolloSchemaApplicationPlugin::class.java)
    }
}

class ApolloSchemaApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(ViaductApplicationConventionsPlugin::class.java)
        val viaduct = project.extensions.getByType(ViaductApplicationConventions::class.java)
        val generate = project.tasks.register<GenerateApolloSchema>("generateApolloSchema") {
            group = "viaduct"
            description = "Generate Apollo protocol SDL for final-schema assembly. Runtime resolvers are supplied separately."
            centralSchemaDirectory.set(viaduct.centralSchemaDirectory)
            outputFile.set(project.layout.buildDirectory.file("generated/apollo/schema.graphqls"))
        }
        viaduct.pluginFinalSchema.into("$PLUGIN_ID/generated/$PLUGIN_ID") {
            from(generate.flatMap { task -> task.outputFile })
        }
    }
}

internal const val PLUGIN_ID = "com.airbnb.viaduct.apollo-schema"
