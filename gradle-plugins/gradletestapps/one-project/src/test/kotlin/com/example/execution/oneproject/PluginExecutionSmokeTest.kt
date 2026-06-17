package com.example.execution.oneproject

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.service.BasicViaductFactory
import viaduct.service.api.ExecutionInput

class PluginExecutionSmokeTest {
    private val buildDir = Path.of(System.getProperty("projectBuildDir"))

    @Test
    fun generatedSchemaAndResolverOutputsExist() {
        assertExists(buildDir.resolve("viaduct/centralSchema/BUILTIN_SCHEMA.graphqls"))
        val baseSchemaFile = buildDir.resolve("viaduct/centralSchema/schemabase/directives.graphqls")
        val commonSchemaFile = buildDir.resolve("viaduct/centralSchema/common/common.graphqls")
        val partitionSchemaFile = buildDir.resolve("viaduct/centralSchema/partition/resolvers/graphql/schema.graphqls")

        assertExists(baseSchemaFile)
        assertExists(commonSchemaFile)
        assertExists(partitionSchemaFile)
        assertExists(
            buildDir.resolve(
                "generated-sources/viaduct/resolverBases/" +
                    "com/example/execution/oneproject/resolvers/QueryResolvers.kt"
            )
        )
        assertExists(
            buildDir.resolve(
                "generated-sources/viaduct/resolverBases/" +
                    "com/example/execution/oneproject/resolvers/MutationResolvers.kt"
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
                "META-INF/viaduct/modules/com.example.execution.oneproject.resolvers.json"
        )

        assertExists(configFile)

        val contents = configFile.readText()
        assertTrue(contents.contains("GreetingResolver"))
        assertTrue(contents.contains("AuthorResolver"))
        assertTrue(contents.contains("EchoMutationResolver"))
    }

    @Test
    fun queriesAndMutationsExecuteThroughViaduct() {
        val viaduct = BasicViaductFactory.create()

        val queryResult = viaduct.execute(
            ExecutionInput.create("query { greeting author }")
        )
        assertTrue(queryResult.errors.isEmpty(), "Expected query execution without errors: ${queryResult.errors}")
        assertEquals(
            mapOf(
                "greeting" to "hello from one-project",
                "author" to "gradletestapps",
            ),
            queryResult.getData(),
        )

        val mutationResult = viaduct.execute(
            ExecutionInput.create("""mutation { echo(message: "plugin e2e") }""")
        )
        assertTrue(mutationResult.errors.isEmpty(), "Expected mutation execution without errors: ${mutationResult.errors}")
        assertEquals(mapOf("echo" to "plugin e2e"), mutationResult.getData())
    }

    @Test
    fun invalidSyntaxProducesParseError() {
        val viaduct = BasicViaductFactory.create()

        val result = viaduct.execute(
            ExecutionInput.create("query { }")
        )

        assertNull(result.getData())
        assertTrue(result.errors.isNotEmpty(), "Expected parse errors for invalid syntax")
        assertTrue(result.errors.first().message.contains("Invalid syntax"))
    }

    private fun assertExists(path: Path) {
        assertTrue(path.exists(), "Expected generated output to exist: $path")
    }
}
