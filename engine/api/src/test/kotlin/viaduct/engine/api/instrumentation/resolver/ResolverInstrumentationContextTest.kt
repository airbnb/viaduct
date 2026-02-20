@file:Suppress("ForbiddenImport")

package viaduct.engine.api.instrumentation.resolver

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ResolverInstrumentationContextTest {
    private val state = object : ViaductResolverInstrumentation.InstrumentationState {}
    private val instrumentation = ViaductResolverInstrumentation.DEFAULT

    @Test
    fun `context element is retrievable from coroutine context`() =
        runBlocking {
            val ctx = ResolverInstrumentationContext(instrumentation, state)
            withContext(ctx) {
                val retrieved = coroutineContext[ResolverInstrumentationContext]
                assertSame(ctx, retrieved)
                assertSame(instrumentation, retrieved!!.instrumentation)
                assertSame(state, retrieved.state)
            }
        }

    @Test
    fun `context element is absent when not set`() =
        runBlocking {
            val retrieved = coroutineContext[ResolverInstrumentationContext]
            assertNull(retrieved)
        }
}
