package viaduct.engine.runtime.instrumentation

import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.engine.api.instrumentation.transformDataFetcherResult

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

    @Test
    fun `transforms synchronous null results`() {
        val result = transformDataFetcherResult(null) {
            assertNull(it)
            "transformed"
        }

        assertEquals("transformed", result)
    }

    @Test
    fun `waits for both fetcher and transformation futures`() {
        val fetched = CompletableFuture<String>()
        val transformed = CompletableFuture<String>()
        val result = transformDataFetcherResult(fetched) {
            assertEquals("fetched", it)
            transformed
        } as CompletionStage<*>

        assertFalse(result.toCompletableFuture().isDone)
        fetched.complete("fetched")
        assertFalse(result.toCompletableFuture().isDone)
        transformed.complete("transformed")

        assertEquals("transformed", result.toCompletableFuture().join())
    }

    @Test
    fun `propagates transformation future failures`() {
        val failure = IllegalStateException("transformation failed")
        val result = transformDataFetcherResult(CompletableFuture.completedFuture("fetched")) {
            CompletableFuture.failedFuture<Any?>(failure)
        } as CompletionStage<*>

        val thrown = assertThrows(CompletionException::class.java) { result.toCompletableFuture().join() }

        assertSame(failure, thrown.cause)
    }
}
