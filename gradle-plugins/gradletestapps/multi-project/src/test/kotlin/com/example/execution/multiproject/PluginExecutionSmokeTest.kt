package com.example.execution.multiproject

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.service.BasicViaductFactory
import viaduct.service.api.ExecutionInput

class PluginExecutionSmokeTest {
    private val buildDir = Path.of(System.getProperty("projectBuildDir"))
    private val alphaBuildDir = Path.of(System.getProperty("alphaBuildDir"))
    private val betaBuildDir = Path.of(System.getProperty("betaBuildDir"))

    @Test
    fun generatedSchemaAndResolverOutputsExist() {
        assertExists(buildDir.resolve("viaduct/centralSchema/BUILTIN_SCHEMA.graphqls"))
        val alphaPartitionSchemaFile = buildDir.resolve("viaduct/centralSchema/partition/alpha/graphql/schema.graphqls")
        val betaPartitionSchemaFile = buildDir.resolve("viaduct/centralSchema/partition/beta/graphql/schema.graphqls")

        assertExists(alphaPartitionSchemaFile)
        assertExists(betaPartitionSchemaFile)
        assertExists(
            alphaBuildDir.resolve(
                "generated-sources/viaduct/resolverBases/" +
                    "com/example/execution/multiproject/alpha/QueryResolvers.kt"
            )
        )
        assertExists(
            betaBuildDir.resolve(
                "generated-sources/viaduct/resolverBases/" +
                    "com/example/execution/multiproject/beta/QueryResolvers.kt"
            )
        )
        assertExists(
            betaBuildDir.resolve(
                "generated-sources/viaduct/resolverBases/" +
                    "com/example/execution/multiproject/beta/MutationResolvers.kt"
            )
        )

        assertTrue(alphaPartitionSchemaFile.readText().contains("greeting: String"))
        assertTrue(alphaPartitionSchemaFile.readText().contains("secretGreeting: String @tenantLocal"))
        assertTrue(betaPartitionSchemaFile.readText().contains("author: String"))
        assertTrue(betaPartitionSchemaFile.readText().contains("echo(message: String!): String"))
    }

    @Test
    fun tenantModuleConfigsContainKspExtractedResolvers() {
        val alphaConfigFile = alphaBuildDir.resolve(
            "generated-resources/viaduct-registry/" +
                "META-INF/viaduct/modules/com.example.execution.multiproject.alpha.json"
        )
        val betaConfigFile = betaBuildDir.resolve(
            "generated-resources/viaduct-registry/" +
                "META-INF/viaduct/modules/com.example.execution.multiproject.beta.json"
        )

        assertExists(alphaConfigFile)
        assertExists(betaConfigFile)

        assertTrue(alphaConfigFile.readText().contains("GreetingResolver"))
        assertTrue(alphaConfigFile.readText().contains("SecretGreetingResolver"))
        assertTrue(alphaConfigFile.readText().contains("secretGreeting"))
        val betaContents = betaConfigFile.readText()
        assertTrue(betaContents.contains("AuthorResolver"))
        assertTrue(betaContents.contains("EchoMutationResolver"))
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
                "greeting" to "hello from multi-project alpha",
                "author" to "hello from multi-project beta",
            ),
            queryResult.getData(),
        )

        val mutationResult = viaduct.executeAsync(
            ExecutionInput.create("""mutation { echo(message: "plugin e2e") }""")
        ).join()
        assertTrue(mutationResult.errors.isEmpty(), "Expected mutation execution without errors: ${mutationResult.errors}")
        assertEquals(mapOf("echo" to "plugin e2e"), mutationResult.getData())
    }

    private fun assertExists(path: Path) {
        assertTrue(path.exists(), "Expected generated output to exist: $path")
    }
}
