package viaduct.engine.runtime.instrumentation

import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase

class ViaductInstrumentationBaseTest {
    val subject: IViaductInstrumentation.WithInstrumentDataFetcher =
        object :
            ViaductInstrumentationBase(), IViaductInstrumentation.WithInstrumentDataFetcher {
            override fun instrumentDataFetcher(
                dataFetcher: DataFetcher<*>,
                parameters: InstrumentationFieldFetchParameters,
                state: InstrumentationState?
            ): DataFetcher<*> {
                return transformResult(dataFetcher) {
                    "$it transformed"
                }
            }
        }

    @Test
    fun `transformResult transforms non-future fetcher`() {
        val result =
            subject.instrumentDataFetcher(
                { "result" },
                mockk(),
                null
            ).get(mockk())
        assertEquals("result transformed", result)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `transformResult transforms future fetcher`() {
        val result =
            subject.instrumentDataFetcher(
                { CompletableFuture.completedFuture("result") },
                mockk(),
                null
            ).get(mockk())
        assert(result is CompletableFuture<*>)
        assertEquals("result transformed", (result as CompletableFuture<Any>).join())
    }
}
