package viaduct.gradle

import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.gradle.ext.settings
import org.jetbrains.gradle.ext.taskTriggers
import viaduct.apiannotations.InternalApi
import viaduct.gradle.shared.BuildFlags

@InternalApi
object ViaductPluginCommon {
    val APPLICATION_PLUGIN_IDS = listOf(
        "com.airbnb.viaduct.application-gradle-plugin",
    )

    val VIADUCT_KIND: Attribute<String> =
        Attribute.of("viaduct.kind", String::class.java)

    object Kind {
        const val SCHEMA_PARTITION = "schema-partition"
        const val CENTRAL_SCHEMA = "central-schema"
        const val KOTLIN_GRT_CLASSES = "kotlin-grt-classes"
        const val JAVA_GRT_CLASSES = "java-grt-classes"
    }

    /** Fixed package for generated Java GRT types. Mirrors the Kotlin convention of a single shared GRT package. */
    const val JAVA_GRT_PACKAGE = "viaduct.java.grts"

    object Configs {
        /** Root/app: dependency bucket for Viaduct module projects used by this application. */
        const val VIADUCT_MODULES = "viaductModules"

        /** Root/app: resolvable configuration that modules add their schema partitions to. */
        const val ALL_SCHEMA_PARTITIONS_INCOMING = "viaductAllSchemaPartitionsIn"

        /** Root/app: consumable configuration for the central schema file. */
        const val CENTRAL_SCHEMA_OUTGOING = "viaductCentralSchema"

        /** Root/app: consumable configuration for the Kotlin GRT jar. */
        const val GRT_CLASSES_KOTLIN_OUTGOING = "viaductKotlinGRTClasses"

        /** Root/app: consumable configuration for the Java GRT jar. */
        const val GRT_CLASSES_JAVA_OUTGOING = "viaductJavaGRTClasses"

        /** Module: consumable configuration for a modules schema partition. */
        const val SCHEMA_PARTITION_OUTGOING = "viaductSchemaPartition"

        /** Module: resolvable configuration for the central schema file. */
        const val CENTRAL_SCHEMA_INCOMING = "viaductCentralSchemaIn"

        /** Module: resolvable configuration for the Kotlin GRT jar. */
        const val GRT_CLASSES_KOTLIN_INCOMING = "viaductKotlinGRTClassesIn"

        /** Module: resolvable configuration for the Java GRT jar. */
        const val GRT_CLASSES_JAVA_INCOMING = "viaductJavaGRTClassesIn"
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

    /** Codegen tool classpath: resolves `com.airbnb.viaduct:buildtime`. */
    fun Project.createOrGetCodegenClasspath(pluginVersion: String): Configuration = createOrGetToolClasspath("viaductCodegenClasspath", "com.airbnb.viaduct:buildtime:$pluginVersion")

    /** Java codegen tool classpath: `com.airbnb.viaduct:javaapi-buildtime:$pluginVersion`. */
    fun Project.createOrGetJavaCodegenClasspath(pluginVersion: String): Configuration = createOrGetToolClasspath("viaductJavaCodegenClasspath", "com.airbnb.viaduct:javaapi-buildtime:$pluginVersion")

    /**
     * Compile classpath for the generated Java GRT sources: `com.airbnb.viaduct:javaapi-api:$pluginVersion`.
     * Needed because javac on the generated GRT sources must resolve `JavaInputBase`/`JavaObjectBase`.
     */
    fun Project.createOrGetJavaGRTCompileClasspath(pluginVersion: String): Configuration = createOrGetToolClasspath("viaductJavaGRTCompileClasspath", "com.airbnb.viaduct:javaapi-api:$pluginVersion")

    fun Project.configureIdeaIntegration(generateGRTsTask: TaskProvider<*>) {
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

    fun Project.validateApplicationProjectPlacement(): ViaductApplicationTopology {
        val topology = requireViaductTopology("com.airbnb.viaduct.application-gradle-plugin")
        if (!topology.isApplicationProject(path)) {
            throw GradleException(
                "Project ${prettyPath()} cannot apply 'com.airbnb.viaduct.application-gradle-plugin' " +
                    "because the Viaduct settings topology declares it as a module of application " +
                    "'${topology.applicationProjectPath}', not as an application project.",
            )
        }
        return topology
    }

    fun Project.validateModuleProjectPlacement(modulePluginId: String): ViaductApplicationTopology {
        val topology = requireViaductTopology(modulePluginId)
        if (!topology.isModuleProject(path)) {
            throw GradleException(
                "Project ${prettyPath()} cannot apply '$modulePluginId' because the Viaduct settings " +
                    "topology declares it as an application project, not as a module project. " +
                    "For a self-contained application module, add includeModule { project(\"$path\") } " +
                    "to the includeViaductApplication declaration.",
            )
        }
        return topology
    }

    fun Project.requireViaductTopology(pluginId: String): ViaductApplicationTopology =
        requireViaductTopologyService().topologyFor(path)
            ?: throw GradleException(
                "Project ${prettyPath()} applies '$pluginId' but is not declared in the Viaduct " +
                    "settings topology. Apply 'com.airbnb.viaduct.settings-gradle-plugin' in " +
                    "settings.gradle.kts and declare the project with includeViaductApplication { ... }.",
            )

    fun Project.requireViaductTopologyModuleProjectPaths(): Set<String> {
        val service = requireViaductTopologyService()
        return service.moduleProjectPaths()
    }

    private fun Project.requireViaductTopologyService(): ViaductTopologyService {
        val registration = gradle.sharedServices.registrations.findByName(ViaductTopologyService.NAME)
            ?: throw GradleException(
                "Viaduct settings topology is required but shared service " +
                    "'${ViaductTopologyService.NAME}' is not registered. Apply " +
                    "'com.airbnb.viaduct.settings-gradle-plugin' in settings.gradle.kts and declare " +
                    "the Viaduct application topology with includeViaductApplication { ... }.",
            )

        return registration.service.get() as ViaductTopologyService
    }

    fun Project.prettyPath(): String = if (path == ":") ": (root)" else path
}

@InternalApi
const val VIADUCT_APPLICATION_OUTPUTS_EXTENSION_NAME = "viaductApplicationOutputs"

@InternalApi
data class ViaductApplicationOutputProviders(
    val centralSchemaDirectory: Provider<Directory>,
    val kotlinGrtJar: Provider<RegularFile>,
    val javaGrtJar: Provider<RegularFile>,
)
