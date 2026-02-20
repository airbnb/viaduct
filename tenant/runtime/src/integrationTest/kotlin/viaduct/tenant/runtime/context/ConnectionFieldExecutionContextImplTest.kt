@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalApi::class)

package viaduct.tenant.runtime.context

import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.api.connection.OffsetLimit
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockReflectionLoader
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.Object
import viaduct.api.types.Query as QueryType
import viaduct.apiannotations.ExperimentalApi
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.select.Query
import viaduct.tenant.runtime.select.SelectTestFeatureAppTest

class ConnectionFieldExecutionContextImplTest : ContextTestBase() {
    private val queryObject = mockk<Query>()

    private object ConnArgs : ConnectionArguments {
        override fun toOffsetLimit(defaultPageSize: Int) = OffsetLimit(0, defaultPageSize)

        override fun validate() {}
    }

    private fun mk(
        obj: Object = Obj,
        query: QueryType = Q,
        args: ConnectionArguments = ConnArgs,
        globalIDCodec: GlobalIDCodec = GlobalIDCodecDefault,
        selectionSet: SelectionSet<CompositeOutput> = noSelections,
    ): ConnectionFieldExecutionContextImpl<QueryType> {
        val wrapper = createMockingWrapper(
            schema = SelectTestFeatureAppTest.schema,
            queryMock = queryObject
        )

        @Suppress("UNCHECKED_CAST")
        return ConnectionFieldExecutionContextImpl(
            MockInternalContext(
                SelectTestFeatureAppTest.schema,
                globalIDCodec,
                MockReflectionLoader(Query.Reflection)
            ),
            wrapper,
            selectionSet as SelectionSet<Connection<*, *>>,
            null,
            args,
            obj,
            query,
            syncObjectValueGetter = null,
            syncQueryValueGetter = null,
            objectCls = Object::class,
            queryCls = QueryType::class,
        )
    }

    @Test
    fun `objectValue is correct`() =
        runBlockingTest {
            val ctx = mk()
            assertEquals(Obj, ctx.objectValue)
        }

    @Test
    fun `queryValue is correct`() =
        runBlockingTest {
            val ctx = mk()
            assertEquals(Q, ctx.queryValue)
        }

    @Test
    fun `arguments is correct`() =
        runBlockingTest {
            val ctx = mk()
            assertSame(ConnArgs, ctx.arguments)
        }

    @Test
    fun `selections is correct`() =
        runBlockingTest {
            val ctx = mk()
            assertEquals(SelectionSet.NoSelections, ctx.selections())
        }

    @Test
    fun `implements InternalContext`() {
        val ctx = mk()
        val internalCtx = MockInternalContext(
            SelectTestFeatureAppTest.schema,
            GlobalIDCodecDefault,
            MockReflectionLoader(Query.Reflection)
        )
        assertEquals(internalCtx.schema, ctx.schema)
    }
}
