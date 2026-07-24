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
        assertEquals(base.path, userDefinition.sourceLocation.sourceName)
        val userExtension = registry.objectTypeExtensions().getValue("User").single()
        assertEquals(extension.path, userExtension.sourceLocation.sourceName)
        assertEquals(extension.path, userExtension.fieldDefinitions.single().sourceLocation.sourceName)

        val schema = ViaductSchema.fromTypeDefinitionRegistry(registry)
        val user = schema.types.getValue("User") as ViaductSchema.Record
        assertEquals(base.path, user.fields.single { it.name == "id" }.sourceLocation?.sourceName)
        assertEquals(extension.path, user.fields.single { it.name == "name" }.sourceLocation?.sourceName)
    }
}
