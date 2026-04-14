package viaduct.tenant.codegen.cli

import java.io.File
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.tenant.codegen.util.ZipUtil.zipAndWriteChildren
import viaduct.tenant.codegen.util.ZipUtil.zipAndWriteChildrenAsRoot

class ZipUtilTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `zipAndWriteChildrenAsRoot archives only root children`() {
        val source = tempDir.resolve("generated").apply { mkdirs() }
        source.resolve("Root.class").writeText("root")
        source.resolve("nested").apply { mkdirs() }
            .resolve("Nested.class")
            .writeText("nested")

        val output = tempDir.resolve("root.zip")
        output.zipAndWriteChildrenAsRoot(source)

        ZipFile(output).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertFalse(entries.contains("/"))
            assertFalse(entries.contains("generated/"))
            assertTrue(entries.contains("Root.class"))
            assertTrue(entries.contains("nested/"))
            assertTrue(entries.contains("nested/Nested.class"))
        }
    }

    @Test
    fun `zipAndWriteChildren strips source directory prefix`() {
        val source = tempDir.resolve("generated").apply { mkdirs() }
        source.resolve("TopLevel.class").writeText("top-level")
        source.resolve("nested").apply { mkdirs() }
            .resolve("Nested.class")
            .writeText("nested")

        val output = tempDir.resolve("children.zip")
        output.zipAndWriteChildren(source)

        ZipFile(output).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertFalse(entries.contains("generated/"))
            assertFalse(entries.contains("generated/TopLevel.class"))
            assertTrue(entries.contains("TopLevel.class"))
            assertTrue(entries.contains("nested/"))
            assertTrue(entries.contains("nested/Nested.class"))
            assertEquals(
                "top-level",
                zip.getInputStream(zip.getEntry("TopLevel.class")).bufferedReader().readText()
            )
            assertEquals(
                "nested",
                zip.getInputStream(zip.getEntry("nested/Nested.class")).bufferedReader().readText()
            )
        }
    }
}
