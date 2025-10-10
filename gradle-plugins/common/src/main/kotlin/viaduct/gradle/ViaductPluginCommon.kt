package viaduct.gradle

import java.io.File
import java.net.URLClassLoader
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute

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

        val ALL_ARTIFACTS = setOf(
            "engine-api",
            "engine-runtime",
            "engine-wiring",
            "service-api",
            "service-runtime",
            "service-wiring",
            "tenant-api",
            "tenant-runtime",
            "tenant-codegen",
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

        val DEFAULT_APPLICATION_ARTIFACTS = ALL_ARTIFACTS

        val DEFAULT_MODULE_ARTIFACTS = ALL_ARTIFACTS

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

    fun Project.addViaductTestFixtures(artifacts: Set<String>) {
        artifacts.forEach { artifact ->
            dependencies.add(
                "testImplementation",
                dependencies.testFixtures("${BOM.GROUP_ID}:$artifact")
            )
        }
    }
}
