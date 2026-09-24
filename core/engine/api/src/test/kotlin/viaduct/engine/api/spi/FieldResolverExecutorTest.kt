package viaduct.engine.api.spi

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet

class FieldResolverExecutorTest {
    @Test
    fun `Selector hashCode excludes selections`() {
        val selections = mockk<EngineSelectionSet>()
        val arguments = mapOf("argument" to "value")
        val syncObjectValueGetter: suspend () -> EngineObjectData.Sync = { error("unused") }
        val selector = selector(arguments, selections, syncObjectValueGetter)

        assertEquals(
            31 * System.identityHashCode(syncObjectValueGetter) + arguments.hashCode(),
            selector.hashCode(),
        )
        verify(exactly = 0) { selections.hashCode() }
    }

    @Test
    fun `Selectors with different selections remain distinct despite hash collision`() {
        val syncObjectValueGetter: suspend () -> EngineObjectData.Sync = { error("unused") }
        val first = selector(emptyMap(), mockk(), syncObjectValueGetter)
        val second = selector(emptyMap(), mockk(), syncObjectValueGetter)

        assertNotEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(2, mapOf(first to "first", second to "second").size)
    }

    private fun selector(
        arguments: Map<String, Any?>,
        selections: EngineSelectionSet?,
        syncObjectValueGetter: suspend () -> EngineObjectData.Sync,
    ) = FieldResolverExecutor.Selector(
        arguments = arguments,
        selections = selections,
        syncObjectValueGetter = syncObjectValueGetter,
        syncQueryValueGetter = { error("unused") },
    )
}
