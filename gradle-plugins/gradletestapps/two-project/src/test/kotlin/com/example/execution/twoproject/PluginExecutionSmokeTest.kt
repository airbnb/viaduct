package com.example.execution.twoproject

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.service.BasicViaductFactory
import viaduct.service.api.ExecutionInput

class PluginExecutionSmokeTest {
    @Test
    fun modulePartitionAndApplicationCentralSchemaAreWiredBidirectionally() {
        val applicationBuildDir = Path.of(System.getProperty("projectBuildDir"))
        val resolverBuildDir = Path.of(System.getProperty("resolverBuildDir"))
        val partitionSchema = applicationBuildDir.resolve(
            "viaduct/centralSchema/partition/resolvers/graphql/schema.graphqls"
        )
        val resolverBases = resolverBuildDir.resolve(
            "generated-sources/viaduct/resolverBases/" +
                "com/example/execution/twoproject/resolvers/QueryResolvers.kt"
        )

        assertTrue(partitionSchema.exists(), "Expected the module partition in the application central schema")
        assertTrue(partitionSchema.readText().contains("greeting: String @resolver"))
        assertTrue(resolverBases.exists(), "Expected resolver bases generated from the application central schema")
    }

    @Test
    fun moduleExtensionContributionIsTransportedToTheApplicationSchema() {
        val applicationBuildDir = Path.of(System.getProperty("projectBuildDir"))
        val contributions = applicationBuildDir.resolve("viaduct/centralSchema/schemabase/contributions")

        assertTrue(
            contributions.listDirectoryEntries("*.graphqls").any {
                it.readText().contains("directive @fromModuleExtension")
            },
            "Expected the module-extension contribution in the application central schema",
        )
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
                "greeting" to "hello from two-project",
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
    fun invalidFieldProducesValidationError() {
        val viaduct = BasicViaductFactory.create()

        val result = viaduct.executeAsync(
            ExecutionInput.create("query { notAField }")
        ).join()

        assertNull(result.getData())
        assertTrue(result.errors.isNotEmpty(), "Expected validation errors for undefined field")
        assertTrue(result.errors.first().message.contains("notAField"))
    }
}
