@file:Suppress("DEPRECATION")

package viaduct.engine

import graphql.ExecutionInput as GraphQLExecutionInput
import graphql.ExecutionResult
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.airbnb.EngineGraphQLJavaCompat
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.FullSchema
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.NodeResolverDispatcherImpl
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.instrumentation.resolver.InstrumentedNodeResolverDispatcher
import viaduct.engine.runtime2.Engine2
import viaduct.engine.runtime2.airbnb.DispatcherResolverRegistryFactory
import viaduct.engine.runtime2.airbnb.Engine2ResolverRegistryFactory
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

class EngineFactoryTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `engines forward field and node batch invocations to instrumentation even when resolvers fail`(engine2: Boolean) {
        val batches = ConcurrentLinkedQueue<Pair<ResolverMetadata, Int>>()
        val fieldMetadata = ResolverMetadata.forModern("BatchField")
        val nodeMetadata = ResolverMetadata.forModern("BatchNode", ResolverType.NODE)

        val engine = createEngineWithBatchResolvers(
            engine2 = engine2,
            batchInvocations = batches,
            resolveValueBatch = { selectors, instrumentationContext ->
                instrumentationContext.run { instrumentation.onBatchResolverInvocation(fieldMetadata, selectors.size, state) }
                error("field failure")
            },
            resolveUserNodeBatch = { selectors, instrumentationContext ->
                instrumentationContext.run { instrumentation.onBatchResolverInvocation(nodeMetadata, selectors.size, state) }
                error("node failure")
            },
        )

        val result = executeQuery(engine, "{ value user { name } }")

        assertEquals(2, result.errors.size)
        assertEquals(listOf(fieldMetadata to 1), batches.filter { it.first == fieldMetadata })
        assertEquals(listOf(nodeMetadata to 1), batches.filter { it.first == nodeMetadata })
        assertEquals(2, batches.size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `engines forward field and node batch invocations to instrumentation on success`(engine2: Boolean) {
        val batches = ConcurrentLinkedQueue<Pair<ResolverMetadata, Int>>()
        val fieldMetadata = ResolverMetadata.forModern("BatchField")
        val nodeMetadata = ResolverMetadata.forModern("BatchNode", ResolverType.NODE)

        val engine = createEngineWithBatchResolvers(
            engine2 = engine2,
            batchInvocations = batches,
            resolveValueBatch = { selectors, instrumentationContext ->
                instrumentationContext.run { instrumentation.onBatchResolverInvocation(fieldMetadata, selectors.size, state) }
                selectors.associateWith { Result.success("value") }
            },
            resolveUserNodeBatch = { selectors, instrumentationContext ->
                instrumentationContext.run { instrumentation.onBatchResolverInvocation(nodeMetadata, selectors.size, state) }
                selectors.associateWith { Result.success(userObjectData(name = "Ada")) }
            },
        )

        val result = executeQuery(engine, "{ value user { name } }")

        assertTrue(result.errors.isEmpty())
        assertEquals(
            mapOf("value" to "value", "user" to mapOf("name" to "Ada")),
            result.getData<Map<String, Any?>>(),
        )
        assertEquals(listOf(fieldMetadata to 1), batches.filter { it.first == fieldMetadata })
        assertEquals(listOf(nodeMetadata to 1), batches.filter { it.first == nodeMetadata })
        assertEquals(2, batches.size)
    }

    @ParameterizedTest
    @CsvSource(
        "false, { __typename value }",
        "true, { __typename value }",
        "false, { __typename ... @defer { value } }",
        "true, { __typename ... @defer { value } }",
    )
    fun `engine2 returns complete results for ordinary and deferred queries`(
        incrementalExecution: Boolean,
        query: String,
    ) {
        val schema = createSchemaWithWiring("extend type Query { value: String }")
        val config =
            EngineConfiguration.default.copy(
                flagManager =
                    MockFlagManager.create(
                        FlagManager.Flags.ENGINE2_ENABLED,
                        FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION,
                    ),
            )
        val dispatcherRegistry =
            DispatcherRegistry.Impl(
                fieldResolverDispatchers =
                    mapOf(
                        ("Query" to "value") to
                            FieldResolverDispatcherImpl(
                                MockFieldUnbatchedResolverExecutor(resolverId = "Query.value") { _, _, _, _, _ -> "value" },
                            ),
                    ),
                nodeResolverDispatchers = emptyMap(),
                fieldCheckerDispatchers = emptyMap(),
                typeCheckerDispatchers = emptyMap(),
            )

        val engine =
            EngineFactory(config, dispatcherRegistry).create(
                schema = schema,
                fullSchema = FullSchema(schema),
            )

        assertInstanceOf(Engine2::class.java, engine)
        val execution = DefaultCoroutineInterop.enterThreadLocalCoroutineContext(EmptyCoroutineContext) {
            engine.execute(ExecutionInput(query, requestContext = Any()), incrementalExecution = incrementalExecution)
        }
        val result = try {
            execution.get(5, TimeUnit.SECONDS)
        } finally {
            execution.cancel(true)
        }
        assertFalse(result is IncrementalExecutionResult)
        assertEquals(mapOf("__typename" to "Query", "value" to "value"), result.getData<Map<String, Any?>>())
        assertTrue(result.errors.isEmpty())
    }

    @ParameterizedTest
    @CsvSource(
        "false, same, false",
        "true, same, false",
        "false, omitted, false",
        "true, omitted, false",
        "false, different, false",
        "true, different, false",
        "false, different, true",
        "true, different, true",
    )
    fun `engine selection preserves GraphQL Java compatibility`(
        engine2Enabled: Boolean,
        legacyContext: String,
        nullRequestContext: Boolean,
    ): Unit =
        runTest {
            val requestContext = if (nullRequestContext) null else Any()
            val schema = createSchemaWithWiring("extend type Query { value: String @resolver }")
            val engine = createCompatibilityEngine(schema, engine2Enabled, requestContext)

            assertInstanceOf(if (engine2Enabled) Engine2::class.java else EngineImpl::class.java, engine)
            val compatibility = engine as EngineGraphQLJavaCompat
            val context = compatibility.createEngineExecutionContext(requestContext)
            assertSame(engine, context.engine)
            assertSame(schema, context.scopedSchema)
            assertSame(requestContext, context.requestContext)

            val result = DefaultCoroutineInterop.enterThreadLocalCoroutineContext(coroutineContext) {
                compatibility.getGraphQL().executeAsync(
                    GraphQLExecutionInput.newExecutionInput()
                        .query("{ aliased: value }")
                        .apply {
                            when (legacyContext) {
                                "same" -> context(requestContext)
                                "different" -> context(Any())
                            }
                        }
                        .localContext(CompositeLocalContext.withContexts(context))
                        .build()
                ).await()
            }.await()

            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(mapOf("aliased" to "value"), result.getData())
        }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "unrelated", "unrelated raw", "foreign engine"])
    fun `engine2 uses legacy request context without a compatible local context`(localContext: String): Unit =
        runTest {
            val requestContext = Any()
            val schema = createSchemaWithWiring("extend type Query { value: String @resolver }")
            val engine = createCompatibilityEngine(schema, engine2Enabled = true, requestContext) as EngineGraphQLJavaCompat
            val input = GraphQLExecutionInput.newExecutionInput()
                .query("{ aliased: value }")
                .context(requestContext)
            when (localContext) {
                "unrelated" -> input.localContext(CompositeLocalContext.withContexts(Any()))
                "unrelated raw" -> input.localContext(Any())
                "foreign engine" -> {
                    val otherEngine = createCompatibilityEngine(schema, engine2Enabled = true, Any()) as EngineGraphQLJavaCompat
                    input.localContext(CompositeLocalContext.withContexts(otherEngine.createEngineExecutionContext(Any())))
                }
            }

            val result = DefaultCoroutineInterop.enterThreadLocalCoroutineContext(coroutineContext) {
                engine.getGraphQL().executeAsync(input.build()).await()
            }.await()

            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(mapOf("aliased" to "value"), result.getData())
        }

    private fun createCompatibilityEngine(
        schema: EngineSchema,
        engine2Enabled: Boolean,
        requestContext: Any?,
    ): Engine {
        val config = EngineConfiguration.default.copy(
            engine2ResolverRegistryFactory = if (engine2Enabled) {
                DispatcherResolverRegistryFactory
            } else {
                Engine2ResolverRegistryFactory { _, _, _, _ -> error("Engine1 must ignore the Engine2 registry setting") }
            },
            flagManager = object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED && engine2Enabled
            },
        )
        val dispatcherRegistry = DispatcherRegistry.Impl(
            fieldResolverDispatchers = mapOf(
                ("Query" to "value") to FieldResolverDispatcherImpl(
                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.value") { _, _, _, _, context ->
                        assertSame(requestContext, context.requestContext)
                        "value"
                    },
                ),
            ),
            nodeResolverDispatchers = emptyMap(),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap(),
        )
        return EngineFactory(config, dispatcherRegistry).create(schema = schema, fullSchema = FullSchema(schema))
    }

    private val batchResolverSchema = createSchemaWithWiring(
        """
        extend type Query { value: String, user: User }
        type User implements Node { id: ID!, name: String }
        """.trimIndent()
    )

    private fun createEngineWithBatchResolvers(
        engine2: Boolean,
        batchInvocations: MutableCollection<Pair<ResolverMetadata, Int>>,
        resolveValueBatch: suspend (List<FieldResolverExecutor.Selector>, ResolverInstrumentationContext) -> Map<FieldResolverExecutor.Selector, Result<Any?>>,
        resolveUserNodeBatch: suspend (List<NodeResolverExecutor.Selector>, ResolverInstrumentationContext) -> Map<NodeResolverExecutor.Selector, Result<EngineObjectData>>,
    ): Engine {
        val expectedState = object : ViaductResolverInstrumentation.InstrumentationState {}
        val instrumentation = object : ViaductResolverInstrumentation {
            override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters) = expectedState

            override fun onBatchResolverInvocation(
                metadata: ResolverMetadata,
                batchSize: Int,
                state: ViaductResolverInstrumentation.InstrumentationState?,
            ) {
                assertSame(expectedState, state)
                batchInvocations.add(metadata to batchSize)
            }
        }
        val registry = DispatcherRegistry.Impl(
            fieldResolverDispatchers = mapOf(
                ("Query" to "nodes") to FieldResolverDispatcherImpl(MockFieldUnbatchedResolverExecutor.Null),
                ("Query" to "node") to FieldResolverDispatcherImpl(
                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.node") { arguments, _, _, _, context ->
                        context.createNodeReference(arguments.getValue("id") as String, batchResolverSchema.schema.getObjectType("User"))
                    },
                ),
                ("Query" to "value") to FieldResolverDispatcherImpl(
                    object : MockFieldBatchResolverExecutor(resolverId = "Query.value") {
                        override suspend fun batchResolve(
                            selectors: List<FieldResolverExecutor.Selector>,
                            context: EngineExecutionContext,
                            instrumentationContext: ResolverInstrumentationContext?,
                        ): Map<FieldResolverExecutor.Selector, Result<Any?>> = resolveValueBatch(selectors, requireNotNull(instrumentationContext))
                    },
                ),
                ("Query" to "user") to FieldResolverDispatcherImpl(
                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.user") { _, _, _, _, context ->
                        context.createNodeReference(
                            context.globalIDCodec.serialize("User", "1"),
                            batchResolverSchema.schema.getObjectType("User"),
                        )
                    },
                ),
            ),
            nodeResolverDispatchers = mapOf(
                "User" to InstrumentedNodeResolverDispatcher(
                    NodeResolverDispatcherImpl(
                        object : NodeResolverExecutor {
                            override val typeName = "User"
                            override val isSelective = false
                            override val isBatching = true
                            override val metadata = ResolverMetadata.forModern("BatchNode", ResolverType.NODE)

                            override suspend fun resolve(
                                selectors: List<NodeResolverExecutor.Selector>,
                                context: EngineExecutionContext,
                                instrumentationContext: ResolverInstrumentationContext?,
                            ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> = resolveUserNodeBatch(selectors, requireNotNull(instrumentationContext))
                        },
                    ),
                    instrumentation
                ),
            ),
            fieldCheckerDispatchers = emptyMap(),
            typeCheckerDispatchers = emptyMap(),
        )
        return EngineFactory(
            EngineConfiguration.default.copy(
                flagManager = if (engine2) MockFlagManager.create(FlagManager.Flags.ENGINE2_ENABLED) else FlagManager.Disabled,
                resolverInstrumentation = instrumentation,
            ),
            registry,
        ).create(batchResolverSchema, fullSchema = FullSchema(batchResolverSchema))
    }

    private fun userObjectData(name: String) = createEngineObjectData(batchResolverSchema.schema.getObjectType("User"), mapOf("name" to name))

    private fun executeQuery(
        engine: Engine,
        query: String
    ): ExecutionResult {
        val execution = DefaultCoroutineInterop.enterThreadLocalCoroutineContext(EmptyCoroutineContext) {
            engine.execute(ExecutionInput(query, requestContext = null))
        }
        return try {
            execution.get(5, TimeUnit.SECONDS)
        } finally {
            execution.cancel(true)
        }
    }
}
