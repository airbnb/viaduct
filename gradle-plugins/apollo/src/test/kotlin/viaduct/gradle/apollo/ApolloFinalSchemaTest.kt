package viaduct.gradle.apollo

import java.nio.file.Path
import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import viaduct.apiannotations.InternalApi
import viaduct.gradle.ViaductSchemaValidator
import viaduct.gradle.task.AssembleFinalSchemaTask

@OptIn(InternalApi::class)
class ApolloFinalSchemaTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `scoped custom roots cannot introduce scopes only on extensions`() {
        val schema = tempDir.resolve("schema.graphqls").toFile().apply {
            writeText(
                """
                directive @scope(to: [String!]!) repeatable on OBJECT | INTERFACE | UNION
                schema { query: Shop }
                type Shop
                extend type Shop @scope(to: ["catalog"]) { value: String }
                """.trimIndent()
            )
        }
        val errors = ViaductSchemaValidator(
            Logging.getLogger(javaClass),
            sourceRoot = tempDir.toFile(),
            validateScopeConsistency = true,
        ).validateSchema(listOf(schema))
        assertTrue(errors.any { it.message.contains("Extension definition on type Shop cannot expand scope") }, errors.toString())
    }

    @Test
    fun `final assembly accepts entities whose scopes do not intersect the query root`() {
        val project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build()
        val central = tempDir.resolve("central").toFile()
        central.resolve("schema/plugin/dev.apollo/source/directives.graphqls").apply {
            parentFile.mkdirs()
            writeText(
                "scalar FieldSet directive @key(fields: FieldSet!, resolvable: Boolean = true) repeatable on OBJECT " +
                    "directive @resolver on FIELD_DEFINITION directive @scope(to: [String!]!) repeatable on OBJECT | INTERFACE | UNION"
            )
        }
        val schema = central.resolve("schema/application/source/schema.graphqls").apply {
            parentFile.mkdirs()
            writeText(
                """
                schema { query: Shop }
                type Shop @scope(to: ["catalog"]) { greeting: String }
                type User @scope(to: ["users"]) @key(fields: "id") { id: ID! }
                """.trimIndent()
            )
        }
        val scopes = tempDir.resolve("scopes.yaml").toFile().apply {
            writeText(
                """
                version: 1
                schemaScopes: [catalog, users]
                scopedSchemas:
                  - id: CATALOG
                    scopes: [catalog]
                """.trimIndent()
            )
        }
        val task = project.tasks.register("assembleFinal", AssembleFinalSchemaTask::class.java).get()
        task.centralSchemaDirectory.set(central)
        task.scopesFile.from(scopes)
        task.outputDirectory.set(tempDir.resolve("final").toFile())
        task.assemble()

        val additions = tempDir.resolve("additions").toFile()
        val generated = additions.resolve("schema/plugin/$PLUGIN_ID/generated/$PLUGIN_ID/schema.graphqls").apply {
            parentFile.mkdirs()
            writeText(ApolloSchemaGenerator.generate(central))
        }
        task.pluginSchemaFiles.from(additions)
        task.assemble()

        val output = task.outputDirectory.get().asFile
        assertEquals(generated.readText(), output.resolve(generated.relativeTo(additions)).readText())
        assertEquals(schema.readText(), output.resolve(schema.relativeTo(central)).readText())
    }

    @Test
    fun `final validation accepts plugin scalar ownership and removes stale output for protocol conflicts`() {
        val project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build()
        val central = tempDir.resolve("central").toFile()
        val directives = central.resolve("schema/plugin/dev.apollo/source/directives.graphqls").apply {
            parentFile.mkdirs()
            writeText("scalar FieldSet directive @key(fields: FieldSet!, resolvable: Boolean = true) repeatable on OBJECT directive @resolver on FIELD_DEFINITION")
        }
        val schema = central.resolve("schema/application/source/schema.graphqls").apply {
            parentFile.mkdirs()
            writeText("type Query { value: String } type Product @key(fields: \"id\") { id: ID! }")
        }
        val additions = tempDir.resolve("additions").toFile()
        val generated = additions.resolve("schema/plugin/$PLUGIN_ID/generated/$PLUGIN_ID/schema.graphqls").apply {
            parentFile.mkdirs()
            writeText(ApolloSchemaGenerator.generate(central))
        }
        val task = project.tasks.register("assembleFinal", AssembleFinalSchemaTask::class.java).get()
        task.centralSchemaDirectory.set(central)
        task.pluginSchemaFiles.from(additions)
        task.outputDirectory.set(tempDir.resolve("final").toFile())
        task.assemble()
        val output = task.outputDirectory.get().asFile
        assertTrue(output.resolve(generated.relativeTo(additions)).exists())
        assertEquals(schema.readText(), output.resolve(schema.relativeTo(central)).readText())
        assertEquals(directives.readText(), output.resolve(directives.relativeTo(central)).readText())

        schema.appendText(" type _Service { own: String }")
        assertThrows<GradleException> { task.assemble() }
        assertFalse(output.exists())
        val errors = ViaductSchemaValidator(
            Logging.getLogger(javaClass),
            sourceRoot = tempDir.toFile(),
            finalContributors = listOf(PLUGIN_ID),
        ).validateSchema(listOf(schema, directives, generated))
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.all { it.message.contains(PLUGIN_ID) }, errors.toString())
    }
}
