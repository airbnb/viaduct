@file:Suppress("ForbiddenImport")

package viaduct.java.runtime.bootstrap

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.SelectionsBlockConfig
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.TenantModuleException
import viaduct.java.api.annotations.NodeResolverFor
import viaduct.java.api.annotations.Resolver
import viaduct.java.api.annotations.ResolverFor
import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.context.NodeExecutionContext
import viaduct.java.api.internal.BaseBatchedFieldResolver
import viaduct.java.api.internal.BaseBatchedNodeResolver
import viaduct.java.api.internal.BaseUnbatchedFieldResolver
import viaduct.java.api.internal.BaseUnbatchedNodeResolver
import viaduct.java.api.internal.ObjectBase
import viaduct.java.api.resolvers.FieldResolverBase
import viaduct.java.api.resolvers.FieldValue
import viaduct.java.api.resolvers.NodeResolverBase
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.CompositeOutput
import viaduct.java.api.types.NodeObject
import viaduct.java.api.types.Query
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

/**
 * Unit tests for [ViaductJavaExecutorFactory] — exercises the construction half of the
 * file-based bootstrap path directly from [FieldEntryConfig] / [NodeEntryConfig] config, without a
 * classpath scan. This is the Java twin of the wiring exercised by
 * `ExecutionRegistryTenantAPIBootstrapper` at runtime.
 *
 * The "invocation" tests drive the factory-built executor through its public
 * [FieldResolverExecutor.batchResolve] / [NodeResolverExecutor.resolve] entry points — exactly the
 * way the engine calls them, so both single-item and batch adapters actually run.
 */
class ViaductJavaExecutorFactoryTest {
    private val schema = MockSchema.mk(
        """
        extend type Query {
            testField: String
            selectiveField: String
            fullName: String
        }

        type Person {
            firstName: String!
            lastName: String!
            fullName: String
            age: Int
        }
        """.trimIndent()
    )

    private fun factory() =
        ViaductJavaExecutorFactory(
            codeInjector = CodeInjector.Naive,
            grtPackagePrefix = "viaduct.java.api.grts.nonexistent",
            registry = ExecutionRegistryConfigFile(version = "1", executorFactory = ViaductJavaExecutorFactory::class.java.name),
        )

    // ── Test fixtures ───────────────────────────────────────────────────────

    interface TestQuery : Query

    @ResolverFor(typeName = "Query", fieldName = "testField", isSelective = false)
    abstract class TestFieldResolverBase :
        FieldResolverBase<String, TestQuery, TestQuery, Arguments.NoArguments, CompositeOutput>,
        BaseUnbatchedFieldResolver {
        abstract fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.NoArguments, CompositeOutput>): CompletableFuture<String>

        final override fun invokeFieldResolver(context: FieldExecutionContext<*, *, *, *>): CompletableFuture<*> = resolve(uncheckedCast(context))
    }

    @Resolver
    class TestFieldResolver : TestFieldResolverBase() {
        override fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.NoArguments, CompositeOutput>): CompletableFuture<String> = CompletableFuture.completedFuture("test result")
    }

    @ResolverFor(typeName = "Person", fieldName = "fullName", isSelective = false)
    abstract class PersonFullNameResolverBase :
        FieldResolverBase<String, TestQuery, TestQuery, Arguments.NoArguments, CompositeOutput>,
        BaseUnbatchedFieldResolver {
        abstract fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.NoArguments, CompositeOutput>): CompletableFuture<String>

        final override fun invokeFieldResolver(context: FieldExecutionContext<*, *, *, *>): CompletableFuture<*> = resolve(uncheckedCast(context))
    }

    @Resolver(objectValueFragment = "firstName lastName")
    class PersonFullNameResolver : PersonFullNameResolverBase() {
        override fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.NoArguments, CompositeOutput>): CompletableFuture<String> = CompletableFuture.completedFuture("Full Name")
    }

    class TestNodeObj : NodeObject

    @NodeResolverFor(typeName = "TestNodeType")
    abstract class TestNodeResolverBase : NodeResolverBase<TestNodeObj>, BaseUnbatchedNodeResolver {
        abstract fun resolve(ctx: NodeResolverBase.Context<TestNodeObj>): CompletableFuture<TestNodeObj>

        final override fun invokeNodeResolver(context: NodeExecutionContext<*>): CompletableFuture<*> = resolve(uncheckedCast(context))
    }

    @Resolver
    class TestNodeResolver : TestNodeResolverBase() {
        override fun resolve(ctx: NodeResolverBase.Context<TestNodeObj>): CompletableFuture<TestNodeObj> = CompletableFuture.completedFuture(TestNodeObj())
    }

    // Fixtures whose resolve/batchResolve take a concrete Context class, mirroring Java codegen.

    /** Concrete field Context wrapping the engine-provided [FieldExecutionContext], as codegen emits. */
    class ConcreteFieldContext(
        inner: FieldExecutionContext<*, *, *, *>,
    ) : FieldResolverBase.Context<TestQuery, TestQuery, Arguments.NoArguments, CompositeOutput>
        by uncheckedCast(inner)

    @ResolverFor(typeName = "Query", fieldName = "testField", isSelective = false)
    abstract class ConcreteContextFieldResolverBase :
        FieldResolverBase<String, TestQuery, TestQuery, Arguments.NoArguments, CompositeOutput>,
        BaseUnbatchedFieldResolver {
        abstract fun resolve(ctx: ConcreteFieldContext): CompletableFuture<String>

        final override fun invokeFieldResolver(context: FieldExecutionContext<*, *, *, *>): CompletableFuture<*> = resolve(ConcreteFieldContext(context))
    }

    @Resolver
    class ConcreteContextFieldResolver : ConcreteContextFieldResolverBase() {
        override fun resolve(ctx: ConcreteFieldContext): CompletableFuture<String> {
            // Touch the wrapped context to prove it was constructed and threaded through.
            ctx.getRequestContext()
            return CompletableFuture.completedFuture("invoked")
        }
    }

    @ResolverFor(typeName = "Query", fieldName = "testField", isSelective = false)
    abstract class BatchFieldResolverBase :
        FieldResolverBase<String, TestQuery, TestQuery, Arguments.NoArguments, CompositeOutput>,
        BaseBatchedFieldResolver {
        abstract fun batchResolve(contexts: List<ConcreteFieldContext>): CompletableFuture<Map<ConcreteFieldContext, String>>

        final override fun invokeFieldBatchResolver(contexts: List<FieldExecutionContext<*, *, *, *>>): CompletableFuture<Map<FieldExecutionContext<*, *, *, *>, Any>> {
            val wrappedToOriginal = IdentityHashMap<ConcreteFieldContext, FieldExecutionContext<*, *, *, *>>()
            val wrappedContexts = contexts.map { context ->
                ConcreteFieldContext(context).also { wrappedToOriginal[it] = context }
            }
            return batchResolve(wrappedContexts).thenApply { results ->
                results.entries.associate { (wrapped, value) -> wrappedToOriginal.getValue(wrapped) to value }
            }
        }
    }

    @Resolver
    class BatchFieldResolver : BatchFieldResolverBase() {
        override fun batchResolve(contexts: List<ConcreteFieldContext>): CompletableFuture<Map<ConcreteFieldContext, String>> = CompletableFuture.completedFuture(contexts.associateWith { "batched" })
    }

    /** GRT backed directly by engine data so node-result conversion needs no schema lookup. */
    class TestNodeGRT(data: EngineObjectData.Sync) : ObjectBase(null, data), NodeObject

    /** Concrete node Context wrapping the engine-provided [NodeExecutionContext], as codegen emits. */
    class ConcreteNodeContext(
        internal val inner: NodeExecutionContext<*>,
    ) : NodeResolverBase.Context<TestNodeObj> by uncheckedCast(inner)

    @NodeResolverFor(typeName = "TestNodeType")
    abstract class ConcreteContextNodeResolverBase : NodeResolverBase<TestNodeObj>, BaseUnbatchedNodeResolver {
        abstract fun resolve(ctx: ConcreteNodeContext): CompletableFuture<TestNodeGRT>

        final override fun invokeNodeResolver(context: NodeExecutionContext<*>): CompletableFuture<*> = resolve(ConcreteNodeContext(context))
    }

    /**
     * Node resolver returning a GRT backed by the engine data set in [nextEngineData], so the
     * invocation test can assert the resolved value flows through unchanged.
     */
    @Resolver
    class DataBoundNodeResolver : ConcreteContextNodeResolverBase() {
        override fun resolve(ctx: ConcreteNodeContext): CompletableFuture<TestNodeGRT> {
            // Touch the wrapped context to prove it was constructed and threaded through.
            ctx.getRequestContext()
            return CompletableFuture.completedFuture(TestNodeGRT(nextEngineData!!))
        }

        companion object {
            @Volatile
            var nextEngineData: EngineObjectData.Sync? = null
        }
    }

    @NodeResolverFor(typeName = "TestNodeType")
    abstract class BatchNodeResolverBase :
        NodeResolverBase<TestNodeObj>,
        BaseBatchedNodeResolver<TestNodeGRT> {
        abstract fun batchResolve(contexts: List<ConcreteNodeContext>): CompletableFuture<Map<ConcreteNodeContext, FieldValue<TestNodeGRT>>>

        final override fun invokeNodeBatchResolver(contexts: List<NodeExecutionContext<*>>): CompletableFuture<Map<NodeExecutionContext<*>, FieldValue<TestNodeGRT>>> {
            val wrappedContexts = contexts.map(::ConcreteNodeContext)
            return batchResolve(wrappedContexts).thenApply { results ->
                results.mapKeysTo(linkedMapOf()) { it.key.inner }
            }
        }
    }

    @Resolver
    class DataBoundBatchNodeResolver : BatchNodeResolverBase() {
        override fun batchResolve(contexts: List<ConcreteNodeContext>): CompletableFuture<Map<ConcreteNodeContext, FieldValue<TestNodeGRT>>> =
            CompletableFuture.completedFuture(
                contexts.associateWith { FieldValue.ofValue(TestNodeGRT(nextEngineData!!)) }
            )

        companion object {
            @Volatile
            var nextEngineData: EngineObjectData.Sync? = null
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun fieldEntry(
        typeName: String,
        fieldName: String,
        resolverClass: Class<*>,
        resolverBaseClass: Class<*>,
        isSelective: Boolean = false,
        isBatching: Boolean = false,
        hasArguments: Boolean = false,
        objectSelections: SelectionsBlockConfig? = null,
        querySelections: SelectionsBlockConfig? = null,
    ) = FieldEntryConfig(
        typeName = typeName,
        fieldName = fieldName,
        isBatching = isBatching,
        isSelective = isSelective,
        attribution = resolverClass.simpleName,
        objectSelections = objectSelections,
        querySelections = querySelections,
        tenantAPIData = mapOf(
            "resolverClass" to resolverClass.name,
            "resolverBaseClass" to resolverBaseClass.name,
            "returnTypeName" to null,
            "hasArguments" to hasArguments,
            "queryTypeName" to "Query",
        ),
    )

    private fun nodeEntry(
        typeName: String,
        resolverClass: Class<*>,
        resolverBaseClass: Class<*>,
        isBatching: Boolean = false,
    ) = NodeEntryConfig(
        typeName = typeName,
        isBatching = isBatching,
        isSelective = false,
        attribution = resolverClass.simpleName,
        tenantAPIData = mapOf(
            "resolverClass" to resolverClass.name,
            "resolverBaseClass" to resolverBaseClass.name,
        ),
    )

    private fun mockEngineContext(): EngineExecutionContext =
        mockk {
            every { requestContext } returns null
            every { globalIDCodec } returns GlobalIDCodecDefault
            // Read when building the per-request InternalContext attached to GRTs.
            every { fullSchema } returns mockk()
        }

    private fun fieldSelector(): FieldResolverExecutor.Selector {
        val objectValue = mockk<EngineObjectData.Sync>()
        val queryValue = mockk<EngineObjectData.Sync>()
        return FieldResolverExecutor.Selector(
            arguments = emptyMap(),
            selections = null,
            syncObjectValueGetter = { objectValue },
            syncQueryValueGetter = { queryValue },
        )
    }

    private fun nodeSelector(): NodeResolverExecutor.Selector =
        NodeResolverExecutor.Selector(
            id = GlobalIDCodecDefault.serialize("TestNodeType", "123"),
            selections = mockk<EngineSelectionSet>(),
        )

    // ── Construction tests ────────────────────────────────────────────────────

    @Test
    fun `createFieldResolverExecutor builds an executor with the expected id`() {
        val executor = factory().createFieldResolverExecutor(
            fieldEntry("Query", "testField", TestFieldResolver::class.java, TestFieldResolverBase::class.java),
            schema,
        )

        executor.resolverId shouldBe "Query.testField"
        executor.isSelective.shouldBeFalse()
        executor.objectSelectionSet.shouldBeNull()
        executor.querySelectionSet.shouldBeNull()
    }

    @Test
    fun `createFieldResolverExecutor honors isSelective`() {
        val executor = factory().createFieldResolverExecutor(
            fieldEntry("Query", "selectiveField", TestFieldResolver::class.java, TestFieldResolverBase::class.java, isSelective = true),
            schema,
        )

        executor.isSelective.shouldBeTrue()
    }

    @Test
    fun `createFieldResolverExecutor wires required selections from the registry entry`() {
        val executor = factory().createFieldResolverExecutor(
            fieldEntry(
                "Person",
                "fullName",
                PersonFullNameResolver::class.java,
                PersonFullNameResolverBase::class.java,
                objectSelections = SelectionsBlockConfig(selections = "firstName lastName"),
            ),
            schema,
        )

        executor.objectSelectionSet.shouldNotBeNull()
        executor.querySelectionSet.shouldBeNull()
    }

    @Test
    fun `createFieldResolverExecutor builds a batching executor for an isBatching entry`() {
        val executor = factory().createFieldResolverExecutor(
            fieldEntry("Query", "testField", BatchFieldResolver::class.java, BatchFieldResolverBase::class.java, isBatching = true),
            schema,
        )

        executor.isBatching.shouldBeTrue()
    }

    @Test
    fun `createFieldResolverExecutor rejects batching resolver with stale generated base`() {
        val thrown = assertThrows<TenantModuleException> {
            factory().createFieldResolverExecutor(
                fieldEntry("Query", "testField", TestFieldResolver::class.java, TestFieldResolverBase::class.java, isBatching = true),
                schema,
            )
        }

        assertTrue(thrown.message!!.contains("does not implement BaseBatchedFieldResolver"))
        assertTrue(thrown.message!!.contains("generated resolver base is out of date"))
    }

    @Test
    fun `createFieldResolverExecutor rejects unbatched resolver with stale generated base`() {
        val thrown = assertThrows<TenantModuleException> {
            factory().createFieldResolverExecutor(
                fieldEntry("Query", "testField", BatchFieldResolver::class.java, BatchFieldResolverBase::class.java),
                schema,
            )
        }

        assertTrue(thrown.message!!.contains("does not implement BaseUnbatchedFieldResolver"))
        assertTrue(thrown.message!!.contains("generated resolver base is out of date"))
    }

    @Test
    fun `createNodeResolverExecutor builds a node executor`() {
        val executor = factory().createNodeResolverExecutor(
            nodeEntry("TestNodeType", TestNodeResolver::class.java, TestNodeResolverBase::class.java),
            schema,
        )

        executor.typeName shouldBe "TestNodeType"
        executor.isSelective.shouldBeFalse()
        executor.isBatching.shouldBeFalse()
    }

    @Test
    fun `createNodeResolverExecutor builds a batching node executor for an isBatching entry`() {
        val executor = factory().createNodeResolverExecutor(
            nodeEntry("TestNodeType", DataBoundBatchNodeResolver::class.java, BatchNodeResolverBase::class.java, isBatching = true),
            schema,
        )

        executor.isBatching.shouldBeTrue()
    }

    @Test
    fun `createNodeResolverExecutor rejects batching resolver with stale generated base`() {
        val thrown = assertThrows<TenantModuleException> {
            factory().createNodeResolverExecutor(
                nodeEntry("TestNodeType", TestNodeResolver::class.java, TestNodeResolverBase::class.java, isBatching = true),
                schema,
            )
        }

        assertTrue(thrown.message!!.contains("does not implement BaseBatchedNodeResolver"))
        assertTrue(thrown.message!!.contains("generated resolver base is out of date"))
    }

    @Test
    fun `createNodeResolverExecutor rejects unbatched resolver with stale generated base`() {
        val thrown = assertThrows<TenantModuleException> {
            factory().createNodeResolverExecutor(
                nodeEntry("TestNodeType", DataBoundBatchNodeResolver::class.java, BatchNodeResolverBase::class.java),
                schema,
            )
        }

        assertTrue(thrown.message!!.contains("does not implement BaseUnbatchedNodeResolver"))
        assertTrue(thrown.message!!.contains("generated resolver base is out of date"))
    }

    // Invocation tests drive the factory's unbatched and batched resolver paths.

    @Test
    fun `built field executor invokes the resolver and returns its value`(): Unit =
        runBlocking {
            val executor = factory().createFieldResolverExecutor(
                fieldEntry("Query", "testField", ConcreteContextFieldResolver::class.java, ConcreteContextFieldResolverBase::class.java),
                schema,
            )

            val selector = fieldSelector()
            val results = executor.batchResolve(listOf(selector), mockEngineContext())

            results[selector]!!.getOrThrow() shouldBe "invoked"
        }

    @Test
    fun `built batching field executor invokes batchResolve and returns per-context values`(): Unit =
        runBlocking {
            val executor = factory().createFieldResolverExecutor(
                fieldEntry("Query", "testField", BatchFieldResolver::class.java, BatchFieldResolverBase::class.java, isBatching = true),
                schema,
            )

            val selector = fieldSelector()
            val results = executor.batchResolve(listOf(selector), mockEngineContext())

            results[selector]!!.getOrThrow() shouldBe "batched"
        }

    @Test
    fun `built node executor invokes the resolver and returns engine data`(): Unit =
        runBlocking {
            val engineData = mockk<EngineObjectData.Sync>()
            // DataBoundNodeResolver is bound to specific engine data so we can assert on the result.
            val executor = factory().createNodeResolverExecutor(
                nodeEntry("TestNodeType", DataBoundNodeResolver::class.java, TestNodeResolverBase::class.java),
                schema,
            )
            DataBoundNodeResolver.nextEngineData = engineData

            val selector = nodeSelector()
            val results = executor.resolve(listOf(selector), mockEngineContext())

            results[selector]!!.getOrThrow() shouldBe engineData
        }

    @Test
    fun `built node executor invokes a resolver taking the bare context interface`(): Unit =
        runBlocking {
            // The generated-style adapter passes the engine context to the tenant resolver. The
            // returned TestNodeObj is not an ObjectBase, so conversion fails downstream.
            val executor = factory().createNodeResolverExecutor(
                nodeEntry("TestNodeType", TestNodeResolver::class.java, TestNodeResolverBase::class.java),
                schema,
            )

            val selector = nodeSelector()
            val results = executor.resolve(listOf(selector), mockEngineContext())

            results[selector]!!.isFailure.shouldBeTrue()
        }

    @Test
    fun `built batching node executor invokes batchResolve and returns engine data per selector`(): Unit =
        runBlocking {
            val engineData = mockk<EngineObjectData.Sync>()
            val executor = factory().createNodeResolverExecutor(
                nodeEntry("TestNodeType", DataBoundBatchNodeResolver::class.java, TestNodeResolverBase::class.java, isBatching = true),
                schema,
            )
            DataBoundBatchNodeResolver.nextEngineData = engineData

            val selector = nodeSelector()
            val results = executor.resolve(listOf(selector), mockEngineContext())

            results[selector]!!.getOrThrow() shouldBe engineData
        }

    // ── Argument-class derivation branch ──────────────────────────────────────

    @Test
    fun `createFieldResolverExecutor derives the argument class when the entry has arguments`() {
        // hasArguments=true drives the argumentClassForName branch. The arguments class is absent
        // from the (nonexistent) grt package, so derivation falls back to null via tryOrNull and
        // the executor is still built successfully.
        val executor = factory().createFieldResolverExecutor(
            fieldEntry("Query", "testField", TestFieldResolver::class.java, TestFieldResolverBase::class.java, hasArguments = true),
            schema,
        )

        executor.resolverId shouldBe "Query.testField"
    }

    // ── Failure paths ──────────────────────────────────────────────────────────

    @Test
    fun `createFieldResolverExecutor throws a helpful error when the resolver class cannot be loaded`() {
        val entry = FieldEntryConfig(
            typeName = "Query",
            fieldName = "testField",
            isBatching = false,
            isSelective = false,
            attribution = "Missing",
            tenantAPIData = mapOf(
                "resolverClass" to "com.example.DoesNotExist",
                "resolverBaseClass" to "com.example.DoesNotExistBase",
                "returnTypeName" to null,
                "hasArguments" to false,
                "queryTypeName" to "Query",
            ),
        )

        val e1 = assertThrows<ClassNotFoundException> { factory().createFieldResolverExecutor(entry, schema) }
        e1.shouldBeInstanceOf<ClassNotFoundException>()
        assertTrue(e1.message!!.contains("com.example.DoesNotExist"))
    }

    @Test
    fun `createNodeResolverExecutor throws a helpful error when the node resolver class cannot be loaded`() {
        val entry = NodeEntryConfig(
            typeName = "TestNodeType",
            isBatching = false,
            isSelective = false,
            attribution = "Missing",
            tenantAPIData = mapOf(
                "resolverClass" to "com.example.MissingNode",
                "resolverBaseClass" to "com.example.MissingNodeBase",
            ),
        )

        val e2 = assertThrows<ClassNotFoundException> { factory().createNodeResolverExecutor(entry, schema) }
        e2.shouldBeInstanceOf<ClassNotFoundException>()
        assertTrue(e2.message!!.contains("com.example.MissingNode"))
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> uncheckedCast(value: Any?): T = value as T
