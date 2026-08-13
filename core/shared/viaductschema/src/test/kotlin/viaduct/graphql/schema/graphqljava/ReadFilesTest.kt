package viaduct.graphql.schema.graphqljava

import java.io.File
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

class ReadFilesTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `readTypesFromFiles tolerates missing trailing newline between source files`() {
        val first = File(tempDir, "a.graphqls").apply {
            writeText("union FooOrBar = Foo | Bar")
        }
        val second = File(tempDir, "b.graphqls").apply {
            writeText(
                """
                schema {
                  query: Query
                }
                type Query
                """.trimIndent()
            )
        }

        assertDoesNotThrow {
            readTypesFromFiles(listOf(first, second))
        }
    }

    @Test
    fun `readTypesFromFiles preserves each field's source file`() {
        val base = File(tempDir, "base.graphqls").apply {
            writeText(
                """
                type Query {
                  viewer: User
                }

                type User {
                  id: ID
                }
                """.trimIndent()
            )
        }
        val extension = File(tempDir, "extension.graphqls").apply {
            writeText(
                """
                extend type User {
                  name: String
                }
                """.trimIndent()
            )
        }

        val registry = readTypesFromFiles(listOf(base, extension))

        val userDefinition = registry.types().getValue("User")
        assertEquals(base.invariantSeparatorsPath, userDefinition.sourceLocation.sourceName)
        val userExtension = registry.objectTypeExtensions().getValue("User").single()
        assertEquals(extension.invariantSeparatorsPath, userExtension.sourceLocation.sourceName)
        assertEquals(extension.invariantSeparatorsPath, userExtension.fieldDefinitions.single().sourceLocation.sourceName)

        val schema = ViaductSchema.fromTypeDefinitionRegistry(registry)
        val user = schema.types.getValue("User") as ViaductSchema.Record
        assertEquals(base.invariantSeparatorsPath, user.fields.single { it.name == "id" }.sourceLocation?.sourceName)
        assertEquals(extension.invariantSeparatorsPath, user.fields.single { it.name == "name" }.sourceLocation?.sourceName)
    }

    @Test
    fun `invariantSourceName normalizes a backslash-separated path`() {
        val file = File("central-schema\\partition\\mymodule\\schema.graphqls")

        assertEquals(
            "central-schema/partition/mymodule/schema.graphqls",
            file.invariantSourceName(separator = '\\')
        )
    }

    @Test
    fun `invariantSourceName normalizes a path with mixed separators`() {
        val file = File("central-schema/partition\\mymodule/schema.graphqls")

        assertEquals(
            "central-schema/partition/mymodule/schema.graphqls",
            file.invariantSourceName(separator = '\\')
        )
    }

    @Test
    fun `invariantSourceName does not rewrite backslashes that are not the separator`() {
        val file = File("weird\\name.graphqls")

        assertEquals("weird\\name.graphqls", file.invariantSourceName(separator = '/'))
    }
}
