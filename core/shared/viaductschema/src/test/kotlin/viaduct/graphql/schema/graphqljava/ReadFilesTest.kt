package viaduct.graphql.schema.graphqljava

import java.io.File
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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
}
