@file:Suppress("DEPRECATION")

package viaduct.engine

import graphql.ExecutionInput as GraphQLExecutionInput
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
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.FullSchema
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime2.Engine2
import viaduct.engine.runtime2.airbnb.DispatcherResolverRegistryFactory
import viaduct.engine.runtime2.airbnb.Engine2ResolverRegistryFactory
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

class EngineFactoryTest {
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
}
