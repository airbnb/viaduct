package viaduct.engine.runtime.execution

import graphql.execution.DataFetcherResult
import graphql.execution.FetchedValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime.context.CompositeLocalContext

class GraphQLJavaExtensionsTest {
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
