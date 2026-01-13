package viaduct.tenant.runtime.context

import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.globalid.GlobalID
import viaduct.api.mocks.MockInternalContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.NodeObject
import viaduct.engine.api.mocks.variables
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.globalid.GlobalIDImpl
import viaduct.tenant.runtime.globalid.GlobalIdFeatureAppTest
import viaduct.tenant.runtime.globalid.Query
import viaduct.tenant.runtime.globalid.User
import viaduct.tenant.runtime.select.SelectionSetImpl

@ExperimentalCoroutinesApi
class NodeExecutionContextImplTest : ContextTestBase() {
    private val queryObject = mockk<Query>()
    private val userId: GlobalID<NodeObject> = GlobalIDImpl(User.Reflection, "123")

    private fun mk(
        userId: GlobalID<NodeObject> = this.userId,
        selectionSet: SelectionSet<NodeObject> = mockk<SelectionSet<NodeObject>>()
    ): NodeExecutionContextImpl {
        val wrapper = createMockingWrapper(
            schema = GlobalIdFeatureAppTest.schema,
            queryMock = queryObject
        )

        return NodeExecutionContextImpl(
            MockInternalContext(GlobalIdFeatureAppTest.schema, GlobalIDCodecDefault),
            wrapper,
            selectionSet,
            null, // requestContext
            userId
        )
    }

    @Test
    fun properties() {
        val ctx = mk()
        assertEquals(userId, ctx.id)
    }

    @Test
    fun selectionsFor() {
        val ctx = mk()
        val ss = ctx.selectionsFor(Query.Reflection, "__typename", mapOf("var" to true))
        assertTrue(ss.contains(Query.Reflection.Fields.__typename))
        val inner = (ss as SelectionSetImpl).rawSelectionSet
        assertEquals(mapOf("var" to true), inner.variables())
    }

    @Test
    fun query() =
        runBlockingTest {
            val ctx = mk()
            ctx.selectionsFor(Query.Reflection, "__typename").also {
                assertTrue(it.contains(Query.Reflection.Fields.__typename))

                ctx.query(it).also { result ->
                    assertEquals(queryObject, result)
                }
            }
        }

    @Test
    fun nodeFor() {
        val ctx = mk()
        // Just verify the method can be called without throwing - actual node resolution
        // would require more complex setup of engine execution context mocking
        ctx.nodeFor(userId)
    }
}
