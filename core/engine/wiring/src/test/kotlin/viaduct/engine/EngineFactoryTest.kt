package viaduct.engine

import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.FullSchema
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime2.Engine2
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
}
