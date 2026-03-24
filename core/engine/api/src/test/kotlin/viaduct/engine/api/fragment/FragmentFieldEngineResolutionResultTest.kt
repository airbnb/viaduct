package viaduct.engine.api.fragment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FragmentFieldEngineResolutionResultTest {
    @Test
    fun `test fragment field engine result initialie `() {
        val anyMap = mapOf("" to 1)
        val fragmentFieldEngineResolutionResult = FragmentFieldEngineResolutionResult(
            anyMap
        )
        assert(fragmentFieldEngineResolutionResult.errors.isEmpty())
        assertEquals(anyMap, fragmentFieldEngineResolutionResult.data)
    }

    @Test
    fun `test fragment field engine result empty companion `() {
        val fragmentFieldEngineResolutionResult = FragmentFieldEngineResolutionResult.empty

        assert(fragmentFieldEngineResolutionResult.errors.isEmpty())
        assert(fragmentFieldEngineResolutionResult.data.isEmpty())
    }
}
