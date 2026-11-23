package com.example.execution.multiproject

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.service.BasicViaductFactory
import viaduct.service.api.ExecutionInput

class PluginExecutionSmokeTest {
    private val buildDir = Path.of(System.getProperty("projectBuildDir"))
    private val alphaResolverBasesDir = Path.of(System.getProperty("alphaResolverBasesDir"))
    private val betaResolverBasesDir = Path.of(System.getProperty("betaResolverBasesDir"))

    @Test
    fun foundationCapabilitySuppliesSchemaToAssemblyAndRuntimePackaging() {
        val relative = "plugin/com.airbnb.viaduct.foundation-federation/source/viaduct/federation/foundation.graphqls"
        val sdl = buildDir.resolve("viaduct/centralSchema/viaduct/central-schema/$relative").readText()
        assertTrue(sdl.contains("directive @key(fields: FieldSelectionSet!) repeatable on OBJECT | INTERFACE"))
        assertTrue(sdl.contains("directive @provides(fields: FieldSelectionSet!) on FIELD_DEFINITION"))
        assertEquals(sdl, buildDir.resolve("viaduct/finalSchema/viaduct/final-schema/$relative").readText())
        assertEquals(sdl, javaClass.getResource("/META-INF/viaduct/final-schema/$relative")!!.readText())
        val schema = buildDir.resolve("viaduct/centralSchema/viaduct/central-schema/module/alpha/source/schema.graphqls").readText()
        assertTrue(schema.contains("@key(fields: \"id\")"))
        assertTrue(schema.contains("@provides(fields: \"name\")"))
    }

    @Test
    fun finalAdditionsArePackagedWithoutChangingTheCentralSchema() {
        val relative = "plugin/dev.example.fixture/generated/dev.example.final-generator/final.graphqls"
        assertFalse(buildDir.resolve("viaduct/centralSchema/viaduct/central-schema/$relative").exists())
        val finalSdl = buildDir.resolve("viaduct/finalSchema/viaduct/final-schema/$relative").readText()
        assertTrue(finalSdl.contains("type FixtureFinal"))
        assertTrue(finalSdl.contains("extend interface PluginInputs"))
        assertEquals(finalSdl, javaClass.getResource("/META-INF/viaduct/final-schema/$relative")!!.readText())
        val index = javaClass.getResource("/META-INF/viaduct/resource-index/application/generated/viaduct.core/schema-v1.index")!!.readText()
        assertTrue(index.contains("META-INF/viaduct/final-schema/$relative"))
        val centralRoot = buildDir.resolve("viaduct/centralSchema/viaduct/central-schema")
        centralRoot.toFile().walkTopDown().filter { it.isFile }.forEach { file ->
            assertEquals(file.readText(), buildDir.resolve("viaduct/finalSchema/viaduct/final-schema").resolve(file.toPath().let { centralRoot.relativize(it) }).readText())
        }
    }

    @Test
    fun pluginResourcesAndGeneratedProviderInputsRetainStablePaths() {
        val schema = buildDir.resolve("viaduct/centralSchema/viaduct/central-schema")
        val bundled = schema.resolve("plugin/dev.example.fixture/source/dev/example/fixture/schema/directives.graphqls")
        val generated = schema.resolve("plugin/dev.example.fixture/generated/dev.example.generator/fixture-generated.graphqls")
        assertEquals(
            "scalar FixtureJson\n\ndirective @fixture(payload: FixtureJson) on OBJECT\n\ninterface PluginInputs {\n  inspect(value: FixtureJson, values: [FixtureJson!]): String\n}\n",
            bundled.readText(),
        )
        assertEquals("directive @generatedFixture on OBJECT\n", generated.readText())
    }

    @Test
    fun generatedSchemaAndResolverOutputsExist() {
        assertExists(buildDir.resolve("viaduct/centralSchema/viaduct/central-schema/plugin/viaduct.core/generated/viaduct.core/BUILTIN_SCHEMA.graphqls"))
        val alphaPartitionSchemaFile = buildDir.resolve("viaduct/centralSchema/viaduct/central-schema/module/alpha/source/schema.graphqls")
        val betaPartitionSchemaFile = buildDir.resolve("viaduct/centralSchema/viaduct/central-schema/module/beta/source/schema.graphqls")

        assertExists(alphaPartitionSchemaFile)
        assertExists(betaPartitionSchemaFile)
        assertExists(
            alphaResolverBasesDir.resolve(
                "com/example/execution/multiproject/alpha/resolverbases/QueryResolvers.kt"
            )
        )
        assertExists(
            betaResolverBasesDir.resolve(
                "com/example/execution/multiproject/beta/resolverbases/QueryResolvers.java"
            )
        )
        assertExists(
            betaResolverBasesDir.resolve(
                "com/example/execution/multiproject/beta/resolverbases/MutationResolvers.java"
            )
        )

        assertTrue(alphaPartitionSchemaFile.readText().contains("greeting: String"))
        assertTrue(alphaPartitionSchemaFile.readText().contains("secretGreeting: String @tenantLocal"))
        assertTrue(betaPartitionSchemaFile.readText().contains("author: String"))
        assertTrue(betaPartitionSchemaFile.readText().contains("echo(message: String!): String"))
    }

    @Test
    fun tenantModuleConfigsContainKspAndAptExtractedResolvers() {
        val alphaConfigFile = javaClass.getResource(
            "/META-INF/viaduct/execution-manifest/module/alpha/generated/com.airbnb.viaduct.module-gradle-plugin/com.example.execution.multiproject.alpha.json"
        )!!
        val betaConfigFile = javaClass.getResource(
            "/META-INF/viaduct/execution-manifest/module/beta/generated/com.airbnb.viaduct.module-java-gradle-plugin/com.example.execution.multiproject.beta.json"
        )!!

        assertTrue(alphaConfigFile.readText().contains("GreetingResolver"))
        assertTrue(alphaConfigFile.readText().contains("SecretGreetingResolver"))
        assertTrue(alphaConfigFile.readText().contains("secretGreeting"))
        val betaContents = betaConfigFile.readText()
        assertTrue(betaContents.contains("AuthorResolver"))
        assertTrue(betaContents.contains("EchoMutationResolver"))
        assertTrue(betaContents.contains("viaduct.java.runtime.bootstrap.ViaductJavaExecutorFactory"))
    }

    @Test
    fun resourceIndexContainsEveryModuleRegistry() {
        val registryIndex = buildDir.resolve(
            "generated-resources/viaduct-execution-registry-index/" +
                "viaduct/resource-index/application/generated/viaduct.core/execution-registry-v1.index"
        ).readText()

        assertEquals(
            "META-INF/viaduct/execution-manifest/module/alpha/generated/com.airbnb.viaduct.module-gradle-plugin/com.example.execution.multiproject.alpha.json\n" +
                "META-INF/viaduct/execution-manifest/module/beta/generated/com.airbnb.viaduct.module-java-gradle-plugin/com.example.execution.multiproject.beta.json\n",
            registryIndex,
        )
    }

    @Test
    fun kotlinAndJavaResolversExecuteThroughViaduct() {
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
