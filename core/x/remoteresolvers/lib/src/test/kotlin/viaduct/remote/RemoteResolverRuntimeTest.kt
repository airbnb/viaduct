package viaduct.remote

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockSchema
import viaduct.remote.fixtures.SimpleFieldResolverExecutor
import viaduct.remote.fixtures.SimpleNodeResolverExecutor

class RemoteResolverRuntimeTest {
    @Test
    fun `copies and indexes a complete executor generation`() {
        val firstNode = SimpleNodeResolverExecutor.createUserResolver()
        val replacementNode = SimpleNodeResolverExecutor.createUserResolver()
        val field = SimpleFieldResolverExecutor()
        val nodeExecutors = mutableListOf(firstNode, replacementNode)
        val fieldExecutors = mutableListOf(field)

        val runtime = RemoteResolverRuntime(
            schema = MockSchema.mk("extend type Query { test: String }"),
            nodeExecutors = nodeExecutors,
            fieldExecutors = fieldExecutors,
        )
        nodeExecutors.clear()
        fieldExecutors.clear()

        assertSame(replacementNode, runtime.nodeExecutors[firstNode.typeName])
        assertSame(field, runtime.fieldExecutors[field.resolverId])
    }
}
