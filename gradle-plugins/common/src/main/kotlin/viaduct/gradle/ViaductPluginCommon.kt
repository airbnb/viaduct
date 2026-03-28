package viaduct.gradle

import java.util.Properties
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.tasks.TaskProvider
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.gradle.ext.settings
import org.jetbrains.gradle.ext.taskTriggers
import viaduct.gradle.shared.BuildFlags

object ViaductPluginCommon {
    val VIADUCT_KIND: Attribute<String> =
        Attribute.of("viaduct.kind", String::class.java)

    object Kind {
        const val SCHEMA_PARTITION = "schema-partition"
        const val CENTRAL_SCHEMA = "central-schema"
        const val GRT_CLASSES = "grt-classes"
    }

    object Configs {
        /** Root/app: resolvable configuration that modules add their schema partitions to. */
        const val ALL_SCHEMA_PARTITIONS_INCOMING = "viaductAllSchemaPartitionsIn"

        /** Root/app: consumable configuration for the central schema file. */
        const val CENTRAL_SCHEMA_OUTGOING = "viaductCentralSchema"

        /** Root/app: consumable configuration for the generated GRT files. */
        const val GRT_CLASSES_OUTGOING = "viaductGRTClasses"

        /** Module: consumable configuration for a modules schema partition. */
        const val SCHEMA_PARTITION_OUTGOING = "viaductSchemaPartition"

        /** Module: resolvable configuration for the central schema file. */
        const val CENTRAL_SCHEMA_INCOMING = "viaductCentralSchemaIn"

        /** Module: resolvable configuration for the GRT class files. */
        const val GRT_CLASSES_INCOMING = "viaductGRTClassesIn"
    }

    /**
     * Reads the plugin version from `viaduct-plugin-version.properties`, which is written at
     * build time by `processResources` and is present both in published JARs and in the
     * compiled-resources directory used by Gradle TestKit's `withPluginClasspath()`.
     */
    fun pluginVersion(pluginClass: Class<*>): String {
        val props = Properties()
        pluginClass.getResourceAsStream("/viaduct-plugin-version.properties")
            ?.use { props.load(it) }
        return requireNotNull(props.getProperty("version")) {
            "viaduct-plugin-version.properties not found or has no 'version' key. This is a packaging bug."
        }
    }

    /**
     * Creates (or retrieves) a resolvable tool-classpath Configuration backed by a single
     * Maven coordinate with [defaultDependencies]. Consumer projects may override the default
     * artifact by adding dependencies to the configuration explicitly.
     *
     * In composite builds Gradle auto-substitutes the coordinate with the local project.
     * In external builds it resolves from whatever repository the consumer has configured.
     */
    fun Project.createOrGetToolClasspath(
        configurationName: String,
        notation: String
    ): Configuration {
        val existing = configurations.findByName(configurationName)
        if (existing != null) return existing
        return configurations.create(configurationName).apply {
            setCanBeConsumed(false)
            setCanBeResolved(true)
            defaultDependencies { deps ->
                deps.add(project.dependencies.create(notation))
            }
        }
    }

    /** Codegen tool classpath: `com.airbnb.viaduct:tenant-codegen:$pluginVersion`. */
    fun Project.createOrGetCodegenClasspath(pluginVersion: String): Configuration = createOrGetToolClasspath("viaductCodegenClasspath", "com.airbnb.viaduct:tenant-codegen:$pluginVersion")

    /** Serve tool classpath: `com.airbnb.viaduct:serve:$pluginVersion`. */
    fun Project.createOrGetServeClasspath(pluginVersion: String): Configuration = createOrGetToolClasspath("viaductServeClasspath", "com.airbnb.viaduct:serve:$pluginVersion")

    fun Project.configureIdeaIntegration(generateGRTsTask: TaskProvider<*>) {
        pluginManager.apply("org.jetbrains.gradle.plugin.idea-ext")

        pluginManager.withPlugin("org.jetbrains.gradle.plugin.idea-ext") {
            val ideaExtension = extensions.findByType(IdeaModel::class.java)
            ideaExtension?.project?.settings {
                taskTriggers {
                    beforeSync(generateGRTsTask)
                }
            }
        }
    }

    /**
     * Default build flags used across Viaduct code generation tasks.
     */
    val DEFAULT_BUILD_FLAGS: Map<String, String> = BuildFlags.DEFAULT

    /**
     * Generates the content for a Viaduct build flags file in .bzl format.
     */
    fun buildFlagFileContent(flags: Map<String, String>): String = BuildFlags.toFileContent(flags)
}
