@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.context

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.internal.select.SelectionsLoader
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.tenant.runtime.select.Mutation as SelectMutation

class MutationFieldExecutionContextImplTest {
    private val mutationObject = mockk<SelectMutation>()

    private fun mk(
        inner: FieldExecutionContextImpl<*, *, *, *> = mockk<FieldExecutionContextImpl<Object, Query, Arguments, CompositeOutput>>(),
        mutationLoader: SelectionsLoader<Mutation> = SelectionsLoader.const(mutationObject)
    ) = MutationFieldExecutionContextImpl(
        inner,
        mutationLoader
    )

    @Test
    fun mutation(): Unit =
        runBlocking {
            val ctx = mk()
            assertEquals(mutationObject, ctx.mutation(SelectionSet.empty(SelectMutation.Reflection)))
        }

    @Test
    fun delegation() {
        val inner = mockk<FieldExecutionContextImpl<Object, Query, Arguments, CompositeOutput>>()
        every { inner.arguments }.returns(Arguments.NoArguments)
        val ctx = mk(inner)
        assertEquals(Arguments.NoArguments, ctx.arguments)
    }
}
