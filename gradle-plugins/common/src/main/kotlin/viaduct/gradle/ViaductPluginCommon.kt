package viaduct.gradle

import java.io.File
import java.net.URLClassLoader
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.tasks.TaskProvider
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.gradle.ext.settings
import org.jetbrains.gradle.ext.taskTriggers

object ViaductPluginCommon {
    val VIADUCT_KIND: Attribute<String> =
        Attribute.of("viaduct.kind", String::class.java)

    object BOM {
        const val GROUP_ID = "com.airbnb.viaduct"
        const val ARTIFACT_ID = "bom"

        fun getDefaultVersion(): String {
            val pluginVersion: String? = this::class.java.`package`.implementationVersion

            // Optional: fallback to a resource you embed during development (see §2)
            val version = pluginVersion ?: readVersionFromResource() ?: error(
                "Plugin version unavailable. If you're running from an includedBuild, add the resource fallback (see plugin setup)."
            )

            return version
        }

        private fun readVersionFromResource(): String? =
            this::class.java.getResourceAsStream("/plugin-version.txt")
                ?.bufferedReader()?.use { it.readText().trim() }

        val SHARED_IMPLEMENTATION_ARTIFACTS = setOf(
            "shared-arbitrary",
            "shared-dataloader",
            "shared-utils",
            "shared-logging",
            "shared-deferred",
            "shared-graphql",
            "shared-viaductschema",
            "shared-invariants",
            "shared-codegen",
            "snipped-errors"
        )

        val ALL_APPLICATION_IMPLEMENTATION_ARTIFACTS = setOf(
            "tenant-api",
            "service-api",
            "service-wiring",
        )

        val ALL_TENANT_IMPLEMENTATION_ARTIFACTS = setOf(
            "tenant-api",
            "tenant-runtime", // needs to be here for generated code
        )

        val ALL_TEST_ARTIFACTS = setOf(
            "engine-api",
            "engine-runtime",
            "engine-wiring",
        )

        val DEFAULT_APPLICATION_ARTIFACTS = SHARED_IMPLEMENTATION_ARTIFACTS + ALL_APPLICATION_IMPLEMENTATION_ARTIFACTS

        val DEFAULT_APPLICATION_TEST_ARTIFACTS = ALL_TEST_ARTIFACTS

        val DEFAULT_MODULE_ARTIFACTS = SHARED_IMPLEMENTATION_ARTIFACTS + ALL_TENANT_IMPLEMENTATION_ARTIFACTS

        val DEFAULT_MODULE_TEST_ARTIFACTS = ALL_TEST_ARTIFACTS

        val DEFAULT_TEST_FIXTURES = setOf(
            "tenant-runtime"
        )
    }

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

    // TODO: Must be a better way to do this.  Right now we are limited because we
    // can't include the class loader as a dependency into this plugins project
    // -- see note in settings.gradle.kts.
    fun getClassPathElements(anchor: Class<*>): List<File> =
        (anchor.classLoader as? URLClassLoader)
            ?.urLs
            ?.mapNotNull { url -> runCatching { File(url.toURI()) }.getOrNull() }
            .orEmpty()

    fun Project.applyViaductBOM(version: String) {
        val bomDependency = "${BOM.GROUP_ID}:${BOM.ARTIFACT_ID}:$version"
        dependencies.add("implementation", dependencies.platform(bomDependency))
        pluginManager.withPlugin("java-test-fixtures") {
            dependencies.add("testFixturesImplementation", dependencies.platform(bomDependency))
        }
    }

    fun Project.addViaductDependencies(artifacts: Set<String>) {
        artifacts.forEach { artifact ->
            dependencies.add("implementation", "${BOM.GROUP_ID}:$artifact")
        }
    }

    fun Project.addViaductTestDependencies(artifacts: Set<String>) {
        artifacts.forEach { artifact ->
            dependencies.add("testImplementation", "${BOM.GROUP_ID}:$artifact")
        }
    }

    fun Project.addViaductTestFixtures(artifacts: Set<String>) {
        artifacts.forEach { artifact ->
            dependencies.add(
                "testImplementation",
                dependencies.testFixtures("${BOM.GROUP_ID}:$artifact")
            )
        }
    }

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
    val DEFAULT_BUILD_FLAGS: Map<String, String> = mapOf(
        "enable_binary_schema" to "True"
    )

    /**
     * Generates the content for a Viaduct build flags file in .bzl format.
     *
     * The generated file can be:
     * 1. Imported directly in Starlark (BUILD files, macros, rules)
     * 2. Read as a file in Bazel actions (via ctx.actions.run)
     *
     * @param flags Map of flag names to their values
     * @return The formatted build flags file content
     */
    fun buildFlagFileContent(flags: Map<String, String>): String {
        val flagEntries = flags.entries.joinToString("\n    ") { (key, value) ->
            "\"$key\": \"$value\","
        }
        return """
            |# Viaduct build flags configuration
            |#
            |# This file uses .bzl format so it can be:
            |# 1. Imported directly in Starlark (BUILD files, macros, rules)
            |# 2. Read as a file in Bazel actions (via ctx.actions.run)
            |#
            |# Note: Values are strings ("True"/"False") for compatibility when
            |# passed as action inputs.
            |viaduct_build_flags = {
            |    $flagEntries
            |}
            """.trimMargin()
    }
}
