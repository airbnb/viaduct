package com.example.execution.oneproject

import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.service.BasicViaductFactory
import viaduct.service.api.ExecutionInput

class PluginExecutionSmokeTest {
    private val buildDir = Path.of(System.getProperty("projectBuildDir"))

    @Test
    fun canonicalResourcesAcquireTheirArchivePrefixOnlyWhenPackaged() {
        val manifest = "META-INF/viaduct/execution-manifest/module/resolvers/generated/com.airbnb.viaduct.module-gradle-plugin/com.example.execution.oneproject.resolvers.json"
        assertEquals(1, javaClass.classLoader.getResources(manifest).toList().size)
        JarFile(System.getProperty("moduleJar")).use { jar ->
            assertNotNull(jar.getJarEntry(manifest))
            assertNotNull(jar.getJarEntry("META-INF/viaduct/resource-index/application/generated/viaduct.core/execution-registry-v1.index"))
        }
        listOf("moduleJar", "schemaJar").forEach { property ->
            JarFile(System.getProperty(property)).use { jar ->
                val entries = jar.entries().asSequence().map { it.name }.toList()
                assertFalse(entries.any { it.startsWith("META-INF/META-INF/") || it.startsWith("viaduct/") })
                assertFalse(entries.any { it.startsWith("META-INF/viaduct/modules/") || it.startsWith("META-INF/viaduct/indexes/") })
            }
        }
        listOf(
            "generated-resources/viaduct-registry",
            "generated-resources/viaduct-schema-index",
            "generated-resources/viaduct-execution-registry-index",
            "viaduct/schemaPartition",
            "viaduct/centralSchema",
            "viaduct/finalSchema",
        ).forEach { output ->
            assertFalse(buildDir.resolve(output).toFile().walkTopDown().any { it.name == "META-INF" }, output)
        }
    }

    @Test
    fun generatedSchemaAndResolverOutputsExist() {
        assertExists(buildDir.resolve("classes/viaduct/grt/kotlin/main/viaduct/api/grts/Query.class"))
        assertExists(buildDir.resolve("viaduct/centralSchema/viaduct/central-schema/plugin/viaduct.core/generated/viaduct.core/BUILTIN_SCHEMA.graphqls"))
        val baseSchemaFile = buildDir.resolve("viaduct/centralSchema/viaduct/central-schema/application/source/schemabase/directives.graphqls")
        val commonSchemaFile = buildDir.resolve("viaduct/centralSchema/viaduct/central-schema/application/source/common/common.graphqls")
        val partitionSchemaFile = buildDir.resolve("viaduct/centralSchema/viaduct/central-schema/module/resolvers/source/schema.graphqls")

        assertExists(baseSchemaFile)
        assertExists(commonSchemaFile)
        assertExists(partitionSchemaFile)
        assertExists(
            buildDir.resolve(
                "generated/sources/viaduct/resolver-bases/kotlin/main/" +
                    "com/example/execution/oneproject/resolvers/resolverbases/QueryResolvers.kt"
            )
        )
        assertExists(
            buildDir.resolve(
                "generated/sources/viaduct/resolver-bases/kotlin/main/" +
                    "com/example/execution/oneproject/resolvers/resolverbases/MutationResolvers.kt"
            )
        )

        assertTrue(baseSchemaFile.readText().contains("directive @oneProjectBase"))
        assertTrue(commonSchemaFile.readText().contains("directive @oneProjectCommon"))
        assertTrue(partitionSchemaFile.readText().contains("greeting: String"))
        assertTrue(partitionSchemaFile.readText().contains("echo(message: String!): String"))
    }

    @Test
    fun tenantModuleConfigContainsKspExtractedResolvers() {
        val configFile = buildDir.resolve(
            "generated-resources/viaduct-registry/" +
                "viaduct/execution-manifest/module/resolvers/generated/com.airbnb.viaduct.module-gradle-plugin/com.example.execution.oneproject.resolvers.json"
        )

        assertExists(configFile)

        val contents = configFile.readText()
        assertTrue(contents.contains("GreetingResolver"))
        assertTrue(contents.contains("AuthorResolver"))
        assertTrue(contents.contains("EchoMutationResolver"))
    }

    @Test
    fun resourceIndexesContainGeneratedSchemaAndRegistryPaths() {
        val schemaIndex = buildDir.resolve(
            "generated-resources/viaduct-schema-index/viaduct/resource-index/application/generated/viaduct.core/schema-v1.index"
        ).readText()
        assertTrue(schemaIndex.contains("META-INF/viaduct/final-schema/module/resolvers/source/schema.graphqls"))

        val registryIndex = buildDir.resolve(
            "generated-resources/viaduct-execution-registry-index/" +
                "viaduct/resource-index/application/generated/viaduct.core/execution-registry-v1.index"
        ).readText()
        assertEquals(
            "META-INF/viaduct/execution-manifest/module/resolvers/generated/com.airbnb.viaduct.module-gradle-plugin/com.example.execution.oneproject.resolvers.json\n",
            registryIndex,
        )
    }

    @Test
    fun kotlinModulesDoNotBringUnusedJavaGrtsOntoTheRuntimeClasspath() {
        val loader = javaClass.classLoader
        assertNotNull(loader.getResource("viaduct/api/grts/Query.class"))
        assertNull(loader.getResource("viaduct/java/grts/Query.class"))
    }

    @Test
    fun queriesAndMutationsExecuteThroughViaduct() {
        val viaduct = BasicViaductFactory.create()

        val queryResult = viaduct.executeAsync(
            ExecutionInput.create("query { greeting author }")
        ).join()
        assertTrue(queryResult.errors.isEmpty(), "Expected query execution without errors: ${queryResult.errors}")
        assertEquals(
            mapOf(
                "greeting" to "hello from one-project",
                "author" to "gradletestapps",
            ),
            queryResult.getData(),
        )

        val mutationResult = viaduct.executeAsync(
            ExecutionInput.create("""mutation { echo(message: "plugin e2e") }""")
        ).join()
        assertTrue(mutationResult.errors.isEmpty(), "Expected mutation execution without errors: ${mutationResult.errors}")
        assertEquals(mapOf("echo" to "plugin e2e"), mutationResult.getData())
    }

    @Test
    fun invalidSyntaxProducesParseError() {
        val viaduct = BasicViaductFactory.create()

        val result = viaduct.executeAsync(
            ExecutionInput.create("query { }")
        ).join()

        assertNull(result.getData())
        assertTrue(result.errors.isNotEmpty(), "Expected parse errors for invalid syntax")
        assertTrue(result.errors.first().message.contains("Invalid syntax"))
    }

    private fun assertExists(path: Path) {
        assertTrue(path.exists(), "Expected generated output to exist: $path")
    }
}
