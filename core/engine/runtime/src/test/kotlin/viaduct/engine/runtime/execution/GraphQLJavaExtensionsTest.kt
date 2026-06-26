package viaduct.engine.runtime.execution

import graphql.execution.DataFetcherResult
import graphql.execution.FetchedValue
import graphql.execution.instrumentation.InstrumentationContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime.context.CompositeLocalContext

class GraphQLJavaExtensionsTest {
    @Test
    fun `InstrumentationContext -- onCompletedNullable forwards nullable completion`() {
        val throwable = RuntimeException("failed")
        var capturedResult: Any? = Unit
        var capturedThrowable: Throwable? = null
        val context =
            object : InstrumentationContext<Any> {
                override fun onDispatched() = Unit

                override fun onCompleted(
                    result: Any?,
                    t: Throwable?,
                ) {
                    capturedResult = result
                    capturedThrowable = t
                }
            }

        context.onCompletedNullable(null, throwable)

        assertNull(capturedResult)
        assertSame(throwable, capturedThrowable)
    }

    @Test
    fun `FetchedValue -- compositeLocalContext`() {
        // null
        FetchedValue(null, emptyList(), null).let {
            assertEquals(CompositeLocalContext.empty, it.compositeLocalContext)
        }

        // non-null
        FetchedValue(null, emptyList(), CompositeLocalContext.empty).let {
            assertEquals(CompositeLocalContext.empty, it.compositeLocalContext)
        }

        // err
        FetchedValue(null, emptyList(), Unit).let {
            assertThrows<IllegalStateException> {
                it.compositeLocalContext
            }
        }
    }

    @Test
    fun `DataFetcherResult -- compositeLocalContext`() {
        // null
        DataFetcherResult.newResult<Unit>().build().let {
            assertEquals(CompositeLocalContext.empty, it.compositeLocalContext)
        }

        // non-null
        DataFetcherResult.newResult<Unit>()
            .localContext(CompositeLocalContext.empty)
            .build()
            .let {
                assertEquals(CompositeLocalContext.empty, it.compositeLocalContext)
            }

        // err
        DataFetcherResult.newResult<Unit>()
            .localContext(Unit)
            .build()
            .let {
                assertThrows<IllegalStateException> {
                    it.compositeLocalContext
                }
            }
    }
}
