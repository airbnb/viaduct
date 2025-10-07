@file:Suppress("ForbiddenImport")

package viaduct.api.internal.select

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.context.ExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Query as QueryType

class SelectionsLoaderTest {
    private object Query : QueryType

    private val ctx = mockk<ExecutionContext>()

    @Test
    fun `const`(): Unit =
        runBlocking {
            val loader = SelectionsLoader.const(Query)
            val loaded = loader.load(ctx, SelectionSet.empty(Type.ofClass(Query::class)))
            assertEquals(Query, loaded)
        }

    @Test
    fun `const -- marker interface`(): Unit =
        runBlocking {
            val loader = SelectionsLoader.const<QueryType, Query>(Query)
            val loaded = loader.load(ctx, SelectionSet.empty(Type.ofClass(QueryType::class)))
            assertEquals(Query, loaded)
        }
}
