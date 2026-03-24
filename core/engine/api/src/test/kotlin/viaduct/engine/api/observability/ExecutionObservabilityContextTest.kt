package viaduct.engine.api.observability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.runtime.observability.ExecutionObservabilityContext

class ExecutionObservabilityContextTest {
    @Test
    fun `constructor with attribution`() {
        val attribution = ExecutionAttribution.fromOperation("TestEntity")
        val context = ExecutionObservabilityContext(attribution = attribution)

        assertEquals(attribution, context.attribution)
    }

    @Test
    fun `constructor with null attribution`() {
        val context = ExecutionObservabilityContext(attribution = null)

        assertEquals(null, context.attribution)
    }
}
