@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.tenant.runtime.context

import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockReflectionLoader
import viaduct.api.select.SelectionSet
import viaduct.api.types.Query as QueryType
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.executioncontext.ExecutionContextTestSchema
import viaduct.tenant.runtime.executioncontext.Mutation
import viaduct.tenant.runtime.executioncontext.Query

class MutationFieldExecutionContextImplTest : ContextTestBase() {
    private val mutationObject = mockk<Mutation>()

    private fun mk(): MutationFieldExecutionContextImpl<QueryType, Mutation> {
        val wrapper = createMockingWrapper(
            schema = ExecutionContextTestSchema.schema,
            mutationMock = mutationObject
        )

        return MutationFieldExecutionContextImpl(
            MockInternalContext(
                ExecutionContextTestSchema.schema,
                GlobalIDCodecDefault,
                MockReflectionLoader(Query.Reflection, Mutation.Reflection)
            ),
            wrapper,
            noSelections,
            null, // requestContext
            Args,
            Q,
            syncQueryValueGetter = null,
            queryCls = QueryType::class,
        )
    }

    @Test
    fun mutation() =
        runTest {
            val ctx = mk()
            assertEquals(mutationObject, ctx.mutation("__typename"))
        }

    @Test
    fun delegation() {
        val ctx = mk()
        // Test that basic properties are accessible (delegation works)
        assertEquals(Args, ctx.arguments)
        assertEquals(SelectionSet.NoSelections, ctx.selections())
    }

    @Test
    fun selectionsFor() {
        val ctx = mk()
        // Test that selectionsFor works for mutations (no exception thrown)
        ctx.selectionsFor(Mutation.Reflection, "__typename")
    }
}
