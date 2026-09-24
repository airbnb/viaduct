@file:Suppress("ForbiddenImport")

package viaduct.engine.api.spi

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.spi.MaterializedFieldValueReader.ReadResult

class MaterializedFieldValueReaderTest {
    @Test
    fun `default reads schema name without enumerating selections for a value`() =
        runBlocking {
            val source = mockk<EngineObjectData>()
            coEvery { source.fetchOrNull("name") } returns "Ada"

            assertEquals(ReadResult("Ada", fieldIsMissing = false), MaterializedFieldValueReader.Default.read(source, "name", "selected"))
            coVerify(exactly = 0) { source.fetchSelections() }
        }

    @Test
    fun `default distinguishes explicit null from missing field`() =
        runBlocking {
            val source = mockk<EngineObjectData>()
            coEvery { source.fetchOrNull(any()) } returns null
            coEvery { source.fetchSelections() } returns listOf("name")

            assertEquals(ReadResult(null, fieldIsMissing = false), MaterializedFieldValueReader.Default.read(source, "name", "selected"))
            assertEquals(ReadResult(null, fieldIsMissing = true), MaterializedFieldValueReader.Default.read(source, "missing", "selected"))
        }

    @Test
    fun `default tolerates unavailable selection enumeration`() =
        runBlocking {
            val source = mockk<EngineObjectData>()
            coEvery { source.fetchOrNull("name") } returns null
            coEvery { source.fetchSelections() } throws UnsupportedOperationException()

            assertEquals(ReadResult(null, fieldIsMissing = false), MaterializedFieldValueReader.Default.read(source, "name", "selected"))
        }

    @Test
    fun `default propagates cancellation from selection enumeration`() {
        val source = mockk<EngineObjectData>()
        val cancellation = CancellationException("Cancelled read")
        coEvery { source.fetchOrNull("name") } returns null
        coEvery { source.fetchSelections() } throws cancellation

        assertSame(
            cancellation,
            assertThrows<CancellationException> {
                runBlocking { MaterializedFieldValueReader.Default.read(source, "name", "selected") }
            }
        )
    }

    @Test
    fun `default propagates field read failures`() {
        val source = mockk<EngineObjectData>()
        val failure = IllegalStateException("Resolution failed")
        coEvery { source.fetchOrNull("name") } throws failure

        assertSame(
            failure,
            assertThrows<IllegalStateException> {
                runBlocking { MaterializedFieldValueReader.Default.read(source, "name", "selected") }
            }
        )
    }
}
