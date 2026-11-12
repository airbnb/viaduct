package viaduct.gradle.apollo

import java.nio.file.Path
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class GenerateApolloSchemaTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `task accepts an explicitly selected key alias`() {
        val project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build()
        val input = tempDir.resolve("central").toFile().apply { mkdirs() }
        input.resolve(
            "schema.graphqls"
        ).writeText(
            "scalar FieldSet directive @apollo__key(fields: FieldSet!, resolvable: Boolean = true) repeatable on OBJECT type Query { value: String } type Product @apollo__key(fields: \"id\") { id: ID! }"
        )
        val task = project.tasks.register("generateApolloSchema", GenerateApolloSchema::class.java).get()
        task.centralSchemaDirectory.set(input)
        task.outputFile.set(tempDir.resolve("output/schema.graphqls").toFile())
        task.keyDirectiveName.set("apollo__key")
        task.generate()
        assertTrue(task.outputFile.get().asFile.readText().contains("union _Entity = Product"))
    }

    @Test
    fun `generation replaces removed entities and removes stale output on failure`() {
        val project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build()
        val input = tempDir.resolve("central").toFile().apply { mkdirs() }
        val directives = "scalar FieldSet directive @key(fields: FieldSet!, resolvable: Boolean = true) repeatable on OBJECT "
        val schema = input.resolve("schema.graphqls").apply {
            writeText(directives + "type Query { value: String } type Product @key(fields: \"id\") { id: ID! }")
        }
        val task = project.tasks.register("generateApolloSchema", GenerateApolloSchema::class.java).get()
        task.centralSchemaDirectory.set(input)
        task.outputFile.set(tempDir.resolve("output/schema.graphqls").toFile())
        task.generate()
        val output = task.outputFile.get().asFile
        assertTrue(output.readText().contains("union _Entity = Product"))

        schema.writeText(directives + "type Query { value: String }")
        task.generate()
        assertFalse(output.readText().contains("_Entity"))

        task.keyDirectiveName.set("misspelled_key")
        assertThrows<IllegalArgumentException> { task.generate() }
        assertFalse(output.exists())

        task.keyDirectiveName.set("key")
        task.generate()
        assertTrue(output.exists())
        schema.writeText(directives + "type Product { id: ID! }")
        assertThrows<IllegalStateException> { task.generate() }
        assertFalse(output.exists())
    }
}
