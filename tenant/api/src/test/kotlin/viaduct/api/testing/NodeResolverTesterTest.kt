@file:Suppress("ForbiddenImport")

package viaduct.api.testing

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.api.FieldValue
import viaduct.api.NodeResolverBase
import viaduct.api.context.NodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.VisibleForTest

@OptIn(VisibleForTest::class, InternalApi::class)
class NodeResolverTesterTest {
    companion object {
        private const val TEST_SCHEMA_SDL = """
            type Query {
                node(id: ID!): Node
            }

            interface Node {
                id: ID!
            }

            type NodeTestNode implements Node {
                id: ID!
                name: String!
                value: Int!
            }
        """

        private const val ERROR_MISSING_ID = "id must be set"
        private const val ERROR_EMPTY_IDS = "ids must not be empty"
        private const val ERROR_NO_BATCH_RESOLVE = "does not implement batchResolve"
    }

    private val tester = NodeResolverTester.create<MockNodeObject>(
        ResolverTester.TesterConfig(schemaSDL = TEST_SCHEMA_SDL)
    )

    @Test
    fun `test - simple node resolver`() =
        runBlocking {
            val globalId = createMockGlobalID("test-id-123")

            val result = tester.test(SimpleNodeResolver()) {
                id = globalId
            }

            assertEquals("test-id-123", result.nodeId)
            assertEquals("resolved-test-id-123", result.nodeName)
        }

    @Test
    fun `test - missing id throws exception`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                tester.test(SimpleNodeResolver()) {
                    // id not set
                }
            }
        }

        assertThat(exception.message).contains(ERROR_MISSING_ID)
    }

    @Test
    fun `testBatch - batch resolver with multiple ids`() =
        runBlocking {
            val ids = listOf(
                createMockGlobalID("id-1"),
                createMockGlobalID("id-2"),
                createMockGlobalID("id-3")
            )

            val results = tester.testBatch(BatchNodeResolver()) {
                this.ids = ids
            }

            assertEquals(3, results.size)

            val result0 = results[0].get()
            assertEquals("id-1", result0.nodeId)
            assertEquals("batch-0", result0.nodeName)

            val result1 = results[1].get()
            assertEquals("id-2", result1.nodeId)
            assertEquals("batch-1", result1.nodeName)

            val result2 = results[2].get()
            assertEquals("id-3", result2.nodeId)
            assertEquals("batch-2", result2.nodeName)
        }

    @Test
    fun `testBatch - empty ids throws exception`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                tester.testBatch(BatchNodeResolver()) {
                    ids = emptyList()
                }
            }
        }

        assertThat(exception.message).contains(ERROR_EMPTY_IDS)
    }

    @Test
    fun `test - tester has valid context`() {
        assertNotNull(tester.context)
        assertNotNull(tester.config)
        assertEquals(TEST_SCHEMA_SDL, tester.config.schemaSDL)
    }

    @Test
    fun `test - resolver without batchResolve throws on testBatch`() {
        val ids = listOf(createMockGlobalID("id-1"))

        val exception = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                // SimpleNodeResolver doesn't have batchResolve method
                tester.testBatch(SimpleNodeResolver()) {
                    this.ids = ids
                }
            }
        }

        assertThat(exception.message).contains(ERROR_NO_BATCH_RESOLVE)
    }

    @Test
    fun `test - multiple single resolutions`() =
        runBlocking {
            val ids = listOf("alpha", "beta", "gamma")
            val results = ids.map { idString ->
                tester.test(SimpleNodeResolver()) {
                    id = createMockGlobalID(idString)
                }
            }

            assertEquals(3, results.size)
            assertEquals("resolved-alpha", results[0].nodeName)
            assertEquals("resolved-beta", results[1].nodeName)
            assertEquals("resolved-gamma", results[2].nodeName)
        }

    @Test
    fun `test - with request context`() =
        runBlocking {
            val result = tester.test(RequestContextAwareNodeResolver()) {
                id = createMockGlobalID("test-id")
                requestContext = "some-request-context"
            }

            assertEquals("has-context", result.nodeName)
        }

    /**
     * Creates a mock GlobalID for testing.
     * This is a simple implementation that satisfies the GlobalID contract.
     */
    private fun createMockGlobalID(internalId: String): GlobalID<MockNodeObject> {
        return object : GlobalID<MockNodeObject> {
            override val type: Type<MockNodeObject> = MockNodeObject.Reflection
            override val internalID: String = internalId
        }
    }
}

internal class MockNodeObject(val nodeId: String, val nodeName: String) : NodeObject {
    companion object Reflection : Type<MockNodeObject> {
        override val name: String = "NodeTestNode"
        override val kcls = MockNodeObject::class
    }
}

@OptIn(InternalApi::class)
internal class SimpleNodeResolver : NodeResolverBase<MockNodeObject> {
    class Context(val inner: NodeExecutionContext<MockNodeObject>) :
        NodeExecutionContext<MockNodeObject> by inner

    suspend fun resolve(ctx: Context): MockNodeObject {
        val id = ctx.id.internalID
        return MockNodeObject(id, "resolved-$id")
    }
}

@OptIn(InternalApi::class)
internal class BatchNodeResolver : NodeResolverBase<MockNodeObject> {
    class Context(val inner: NodeExecutionContext<MockNodeObject>) :
        NodeExecutionContext<MockNodeObject> by inner

    suspend fun resolve(ctx: Context): MockNodeObject {
        val id = ctx.id.internalID
        return MockNodeObject(id, "single-$id")
    }

    suspend fun batchResolve(contexts: List<Context>): List<FieldValue<MockNodeObject>> {
        return contexts.mapIndexed { index, ctx ->
            val id = ctx.id.internalID
            FieldValue.ofValue(MockNodeObject(id, "batch-$index"))
        }
    }
}

@OptIn(InternalApi::class)
internal class RequestContextAwareNodeResolver : NodeResolverBase<MockNodeObject> {
    class Context(val inner: NodeExecutionContext<MockNodeObject>) :
        NodeExecutionContext<MockNodeObject> by inner

    suspend fun resolve(ctx: Context): MockNodeObject {
        val id = ctx.id.internalID
        val requestCtx = ctx.requestContext
        val contextInfo = if (requestCtx != null) "has-context" else "no-context"
        return MockNodeObject(id, contextInfo)
    }
}
