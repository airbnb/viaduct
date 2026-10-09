@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.tenant.runtime.context

import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.context.Caller
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockReflectionLoader
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query as QueryType
import viaduct.engine.api.Caller as EngineCaller
import viaduct.errors.FrameworkException
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.executioncontext.ExecutionContextTestSchema
import viaduct.tenant.runtime.executioncontext.Query

class FieldExecutionContextImplTest : ContextTestBase() {
    private val queryObject = mockk<Query>()

    private fun mk(
        args: Arguments = Args,
        globalIDCodec: GlobalIDCodec = GlobalIDCodecDefault,
        selectionSet: SelectionSet<CompositeOutput> = noSelections,
        ownedSelections: Lazy<SelectionSet<CompositeOutput>> = lazyOf(selectionSet),
        caller: EngineCaller? = null,
    ): FieldExecutionContextImpl<QueryType> {
        val wrapper = createMockingWrapper(
            schema = ExecutionContextTestSchema.schema,
            queryMock = queryObject,
            caller = caller,
        )

        return FieldExecutionContextImpl(
            MockInternalContext(
                ExecutionContextTestSchema.schema,
                globalIDCodec,
                MockReflectionLoader(Query.Reflection)
            ),
            wrapper,
            selectionSet,
            null, // requestContext
            args,
            syncObjectValueGetter = null,
            syncQueryValueGetter = null,
            objectCls = Object::class,
            queryCls = QueryType::class,
            ownedSelections = ownedSelections,
        )
    }

    @Test
    fun properties() =
        runTest {
            val ctx = mk()
            assertEquals(Args, ctx.arguments)
            assertEquals(SelectionSet.NoSelections, ctx.selections())
        }

    @Test
    fun `owned selections are projected only on first access`() {
        var projectionCount = 0
        val projected = noSelections
        val ownedSelections = lazy {
            projectionCount += 1
            projected
        }
        val context = mk(ownedSelections = ownedSelections)

        context.selections()
        assertEquals(0, projectionCount)

        assertEquals(projected, context.ownedSelections())
        assertEquals(projected, context.ownedSelections())
        assertEquals(1, projectionCount)
    }

    @Test
    fun `caller maps engine metadata and defaults to null`() {
        assertEquals(null, mk().caller)

        val ctx = mk(
            caller = EngineCaller(
                tenantName = "creator-tenant",
                typeName = "Whatever",
                fieldName = "listing",
            )
        )

        assertEquals(
            Caller("creator-tenant", "Whatever", "listing"),
            ctx.caller,
        )
        assertTrue(ctx.caller === ctx.caller)
    }

    @Test
    fun query() =
        runTest {
            val ctx = mk()
            val result = ctx.query(TypenameQuery)
            assertEquals(queryObject, result)
        }

    @Test
    fun `missing object RSS data is a framework error`() =
        runTest {
            val ctx = mk()

            val error = assertThrows<FrameworkException> { ctx.getObjectValue() }

            assertEquals(
                "Sync object value is not available. This may indicate an internal error in Viaduct.",
                error.message,
            )
        }

    @Test
    fun `missing query RSS data is a framework error`() =
        runTest {
            val ctx = mk()

            val error = assertThrows<FrameworkException> { ctx.getQueryValue() }

            assertEquals(
                "Sync query value is not available. This may indicate an internal error in Viaduct.",
                error.message,
            )
        }
}
