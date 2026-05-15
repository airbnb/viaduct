@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.tenant.runtime.context

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockReflectionLoader
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.Object
import viaduct.api.types.OffsetLimit
import viaduct.api.types.Query as QueryType
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.executioncontext.ExecutionContextTestSchema
import viaduct.tenant.runtime.executioncontext.Query

class ConnectionFieldExecutionContextImplTest : ContextTestBase() {
    private object ConnArgs : ConnectionArguments {
        override fun toOffsetLimit(defaultPageSize: Int) = OffsetLimit(0, defaultPageSize)

        override fun validate() {}
    }

    @Suppress("UNCHECKED_CAST")
    private fun mk(
        args: ConnectionArguments = ConnArgs,
        globalIDCodec: GlobalIDCodec = GlobalIDCodecDefault,
        selectionSet: SelectionSet<CompositeOutput> = noSelections,
    ): ConnectionFieldExecutionContextImpl<QueryType> =
        ConnectionFieldExecutionContextImpl(
            MockInternalContext(
                ExecutionContextTestSchema.schema,
                globalIDCodec,
                MockReflectionLoader(Query.Reflection)
            ),
            createMockingWrapper(schema = ExecutionContextTestSchema.schema),
            selectionSet as SelectionSet<Connection<*, *>>,
            null,
            args,
            syncObjectValueGetter = null,
            syncQueryValueGetter = null,
            objectCls = Object::class,
            queryCls = QueryType::class,
        )

    @Test
    fun `arguments is correct`() =
        runTest {
            val ctx = mk()
            assertSame(ConnArgs, ctx.arguments)
        }

    @Test
    fun `selections is correct`() =
        runTest {
            val ctx = mk()
            assertEquals(SelectionSet.NoSelections, ctx.selections())
        }

    @Test
    fun `implements InternalContext`() {
        val ctx = mk()
        val internalCtx = MockInternalContext(
            ExecutionContextTestSchema.schema,
            GlobalIDCodecDefault,
            MockReflectionLoader(Query.Reflection)
        )
        assertEquals(internalCtx.schema, ctx.schema)
    }
}
