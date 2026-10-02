package viaduct.java.runtime.bridge

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import viaduct.engine.api.Caller
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolveRootFieldReferenceOptions
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.LazyEngineObjectData
import viaduct.engine.runtime.NodeResolverDispatcher
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime.withInvocationContexts
import viaduct.errors.TenantResolverException
import viaduct.java.api.context.ExecutionContext
import viaduct.java.api.context.NodeExecutionContext
import viaduct.java.api.context.ResolverExecutionContext
import viaduct.java.api.context.RootFieldCall
import viaduct.java.api.internal.BaseBatchedFieldResolver
import viaduct.java.api.internal.BaseBatchedNodeResolver
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.internal.ObjectBase
import viaduct.java.api.reflect.RootObjectField
import viaduct.java.api.reflect.Type
import viaduct.java.api.resolvers.FieldValue
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.NodeObject
import viaduct.java.api.types.Query
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class JavaBatchInvocationContextTest {
    class TestNode : ObjectBase, NodeObject {
        constructor(context: InternalContext?, data: EngineObjectData.Sync) : super(context, data)
        constructor(context: InternalContext?, ref: NodeReference) : super(context, ref)
        constructor(context: InternalContext?, ref: RootFieldReference) : super(context, ref)
    }

    class QueryResult(context: InternalContext?, data: EngineObjectData.Sync) : ObjectBase(context, data), Query {
        fun context(): InternalContext = __context()!!
    }

    private val schema = MockSchema.mk(
        """
        extend type Query { root: TestNode, empty: Int }
        type TestNode { id: ID, name: String }
        """.trimIndent()
    )
    private val nodeType = schema.schema.getObjectType("TestNode")
    private val nodeData = ResolvedEngineObjectData(nodeType, mapOf("name" to "node"))
    private val queryData = ResolvedEngineObjectData(schema.schema.queryType, mapOf("empty" to 1))
    private val rootCall = object : RootFieldCall<TestNode> {
        override fun field(): RootObjectField<QueryResult, TestNode, Arguments.NoArguments> =
            RootObjectField.of("root", Type.ofClass(QueryResult::class.java), Type.ofClass(TestNode::class.java), listOf("root"))

        override fun arguments(context: ExecutionContext): Arguments = Arguments.None
    }

    @ParameterizedTest
    @CsvSource("false, 1", "false, 2", "true, 1", "true, 2")
    fun `batch contexts retain subquery attribution and reference callers`(
        nodeBatch: Boolean,
        size: Int
    ) = runTest {
        val subqueries = mutableMapOf<EngineExecutionContext.ExecutionHandle, ExecutionAttribution>()
        val nodeCallers = mutableListOf<Caller?>()
        val rootCallers = mutableListOf<Caller?>()
        val engine = mockk<Engine> {
            coEvery { resolveSelectionSet(any(), any(), any()) } answers {
                subqueries[firstArg()] = thirdArg<ResolveSelectionSetOptions>().attribution
                queryData
            }
            coEvery { resolveRootFieldReference(any(), any(), any(), any(), any()) } answers {
                rootCallers += arg<ResolveRootFieldReferenceOptions>(4).caller
                nodeData
            }
        }
        val nodeDispatcher = mockk<NodeResolverDispatcher> {
            every { resolverMetadata } returns ResolverMetadata.forModern("TestNode", ResolverType.NODE)
            coEvery { resolve(any(), any(), any()) } answers {
                nodeCallers += thirdArg<EngineExecutionContext>().fieldScope.caller
                nodeData
            }
        }
        val registry = mockk<DispatcherRegistry> {
            every { getNodeResolverDispatcher("TestNode") } returns nodeDispatcher
        }
        val requestContext = Any()
        val mocks = ContextMocks(myFullSchema = schema, myEngine = engine, myDispatcherRegistry = registry, myRequestContext = requestContext)
        val handles = List(size) { object : EngineExecutionContext.ExecutionHandle {} }
        val attributions = List(size) { ExecutionAttribution.fromResolver("origin-$it") }
        val callers = List(size) { Caller("tenant-$it", if (nodeBatch) "TestNode" else "Query", if (nodeBatch) null else "field-$it") }
        val invocations = List(size) { i ->
            mocks.invocationContext(
                EngineExecutionContextImpl.FieldExecutionScopeImpl(attribution = attributions[i]),
                callers[i],
                handles[i],
            )
        }
        val nodeRefs = mutableListOf<NodeReference>()
        val rootRefs = mutableListOf<RootFieldReference>()

        val aggregate = mocks.invocationContext(EngineExecutionContextImpl.FieldExecutionScopeImpl(), null, handles.first())
        val results = executeBatch(nodeBatch, invocations, aggregate) { contexts ->
            val queries = contexts.map { context ->
                assertSame(requestContext, context.requestContext)
                val id = context.globalIDFor(Type.ofClass(TestNode::class.java), "referenced")
                nodeRefs += context.ref(id).javaNodeReference!!
                rootRefs += context.ref(rootCall).javaRootFieldReference!!
                context.query("empty", emptyMap(), QueryResult::class.java)
            }
            CompletableFuture.allOf(*queries.toTypedArray()).thenApply {
                queries.map { query ->
                    assertSame(queryData, query.join().javaEngineObjectData)
                    assertSame(mocks.fullSchema, query.join().context().schema)
                    assertSame(GlobalIDCodecDefault, query.join().context().globalIDCodec)
                    TestNode(query.join().context(), nodeData)
                }
            }
        }

        assertEquals(size, results.size)
        results.forEach { assertSame(nodeData, it.getOrThrow()) }
        assertEquals(handles.zip(attributions).toMap(), subqueries)
        val selections = invocations.first().engineSelectionSetFactory.engineSelectionSet("TestNode", "name", emptyMap())
        nodeRefs.forEach { (it as LazyEngineObjectData).resolveData(selections, invocations.first()) }
        rootRefs.forEach { (it as LazyEngineObjectData).resolveData(selections, invocations.first()) }
        assertEquals(callers, nodeCallers)
        assertEquals(callers, rootCallers)
    }

    @ParameterizedTest
    @CsvSource("false, 1", "false, 2", "true, 1", "true, 2")
    fun `whole batch failures retain tenant attribution`(
        nodeBatch: Boolean,
        size: Int
    ) = runTest {
        val context = ContextMocks(myFullSchema = schema).engineExecutionContext
        val failure = IllegalStateException("batch failed")
        val resolve: (List<ResolverExecutionContext>) -> CompletableFuture<List<TestNode>> = {
            CompletableFuture.failedFuture(failure)
        }

        if (nodeBatch) {
            val results = executeBatch(true, List(size) { context }, context, resolve)
            assertEquals(size, results.size)
            results.forEach {
                val error = it.exceptionOrNull() as TenantResolverException
                assertSame(failure, error.cause)
                assertEquals("TestNode", error.resolver)
            }
        } else {
            val error = assertThrows<TenantResolverException> {
                executeBatch(false, List(size) { context }, context, resolve)
            }
            assertSame(failure, error.cause)
            assertEquals("Query.batch", error.resolver)
        }
    }

    private suspend fun executeBatch(
        nodeBatch: Boolean,
        invocations: List<EngineExecutionContext>,
        aggregate: EngineExecutionContext,
        resolve: (List<ResolverExecutionContext>) -> CompletableFuture<List<TestNode>>,
    ): List<Result<Any?>> {
        if (nodeBatch) {
            val selectors = invocations.indices.map { i ->
                NodeResolverExecutor.Selector(GlobalIDCodecDefault.serialize("TestNode", "$i"), aggregate.engineSelectionSetFactory.engineSelectionSet("TestNode", "name", emptyMap()))
            }
            val context = if (selectors.size == 1) invocations.single() else aggregate.withInvocationContexts(selectors.zip(invocations).toMap())
            val resolver = object : BaseBatchedNodeResolver<TestNode> {
                override fun invokeNodeBatchResolver(contexts: List<NodeExecutionContext<*>>): CompletableFuture<Map<NodeExecutionContext<*>, FieldValue<TestNode>>> =
                    resolve(contexts).thenApply { values -> contexts.zip(values.map { FieldValue.ofValue(it) }).toMap() }
            }
            return NodeBatchResolverExecutorImpl(Provider { resolver }, "TestNode", "TestNode")
                .resolve(selectors, context).let { results -> selectors.map { results.getValue(it) } }
        }
        val selectors = invocations.indices.map {
            FieldResolverExecutor.Selector(emptyMap(), null, { nodeData }, { queryData })
        }
        val context = if (selectors.size == 1) invocations.single() else aggregate.withInvocationContexts(selectors.zip(invocations).toMap())
        val resolver = BaseBatchedFieldResolver { contexts ->
            resolve(contexts).thenApply { values -> contexts.zip(values).toMap() }
        }
        return FieldBatchResolverExecutorImpl(Provider { resolver }, "Query.batch", "Query.batch")
            .batchResolve(selectors, context).let { results -> selectors.map { results.getValue(it) } }
    }
}
