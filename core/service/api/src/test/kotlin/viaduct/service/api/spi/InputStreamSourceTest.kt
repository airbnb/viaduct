package viaduct.service.api.spi

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InputStreamSourceTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `fromString opens a stream with the configured charset and name`() {
        val source = InputStreamSource.fromString(
            content = "cafe",
            name = "inline",
            charset = StandardCharsets.UTF_16,
        )

        assertEquals("cafe", source.openStream().use { it.reader(StandardCharsets.UTF_16).readText() })
        assertEquals("InputStreamSource from string named 'inline'", source.toString())
    }

    @Test
    fun `fromString does not include content in toString`() {
        val content = "a".repeat(81)
        val source = InputStreamSource.fromString(content, name = "long-inline")

        assertEquals(content, source.openStream().use { it.reader(StandardCharsets.UTF_8).readText() })
        assertEquals("InputStreamSource from string named 'long-inline'", source.toString())
    }

    @Test
    fun `fromFile opens file stream and identifies file`() {
        val file = File(tempDir, "tenant.json").also { it.writeText("file-content") }
        val source = InputStreamSource.fromFile(file)

        assertEquals("file-content", source.openStream().use { it.reader().readText() })
        assertEquals("InputStreamSource from file '$file'", source.toString())
    }

    @Test
    fun `fromUrl opens URL stream and identifies URL`() {
        val file = File(tempDir, "url-tenant.json").also { it.writeText("url-content") }
        val url = file.toURI().toURL()
        val source = InputStreamSource.fromUrl(url)

        assertEquals("url-content", source.openStream().use { it.reader().readText() })
        assertEquals("InputStreamSource from URL '$url'", source.toString())
    }

    @Test
    fun `fun interface source opens stream`() {
        val source = InputStreamSource { ByteArrayInputStream("content".toByteArray()) }

        assertEquals("content", source.openStream().use { it.reader().readText() })
        assertTrue(source.toString().isNotBlank())
    }
}
