package viaduct.tenant.runtime.context.factory

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.context.NodeExecutionContext

@ExperimentalCoroutinesApi
class NodeResolverContextFactoryTest {
    private val args = MockArgs()

    class Context(inner: NodeExecutionContext<Baz>) : NodeExecutionContext<Baz> by inner

    private val innerFactory = NodeExecutionContextMetaFactory.create(
        globalID = GlobalIDFactory.default,
        selections = SelectionSetFactory.forClass(Baz::class)
    )

    @Test
    fun `forClass -- not Context`() {
        assertThrows<IllegalArgumentException> {
            NodeResolverContextFactory.forClass(NodeExecutionContext::class, innerFactory)
        }
    }

    @Test
    fun `forClass -- is Context`() {
        val ctx = NodeResolverContextFactory
            .forClass(Context::class, innerFactory)
            .make(args.getNodeArgs())
        assertTrue(ctx is Context)
    }
}
