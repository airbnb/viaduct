@file:Suppress("ForbiddenImport")
@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package viaduct.api.context

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.api.globalid.GlobalID
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockResolverExecutionContext
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.engine.api.mocks.MockSchema

class ResolverExecutionContextTest {
    private class RefTestNode : NodeObject

    @Test
    fun `ref delegates to nodeRef`() {
        val expected = RefTestNode()
        val ctx = object : MockResolverExecutionContext<Query>(MockInternalContext(MockSchema.minimal)) {
            @Suppress("UNCHECKED_CAST", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
            override fun <T : NodeObject> nodeRef(globalID: GlobalID<T>): T = expected as T
        }

        assertSame(expected, ctx.ref(mockk<GlobalID<RefTestNode>>()))
    }
}
