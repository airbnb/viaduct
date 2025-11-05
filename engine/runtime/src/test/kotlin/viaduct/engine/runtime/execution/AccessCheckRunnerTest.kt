@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import io.mockk.every
import io.mockk.mockk
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.runtime.CheckerDispatcherImpl
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.getLocalContextForType
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime.objectEngineResult

class AccessCheckRunnerTest {
    val runner = AccessCheckRunner(DefaultCoroutineInterop)

    val mockSupplier = mockk<Supplier<DataFetchingEnvironment>>()
    val mockDataFetchingEnvironment = mockk<DataFetchingEnvironment>()

    @BeforeEach
    fun setUp() {
        every { mockSupplier.get() } returns mockDataFetchingEnvironment
    }

    @Test
    fun `fieldCheck - flag disabled`(): Unit =
        runBlocking {
            val result = checkField(false)
            assertEquals(Value.nullValue, result)
        }

    @Test
    fun `fieldCheck - flag enabled, no checker`(): Unit =
        runBlocking {
            val result = checkField(true)
            assertEquals(Value.nullValue, result)
        }

    @Test
    fun `fieldCheck - flag enabled, checker passes`(): Unit =
        runBlocking {
            withThreadLocalCoroutineContext {
                val result = checkField(true, successCheckerExecutor)
                assertEquals(CheckerResult.Success, result.await())
            }
        }

    @Test
    fun `fieldCheck - flag enabled, checker fails`(): Unit =
        runBlocking {
            withThreadLocalCoroutineContext {
                val result = checkField(true, errorCheckerExecutor)
                val error = result.await()?.asError?.error
                assertTrue(error is IllegalAccessException)
                assertEquals("denied", error.message)
            }
        }

    @Test
    fun `typeCheck - flag disabled`(): Unit =
        runBlocking {
            val result = checkType(false)
            assertEquals(Value.nullValue, result)
        }

    @Test
    fun `typeCheck - flag enabled, no checker`(): Unit =
        runBlocking {
            val result = checkType(true)
            assertEquals(Value.nullValue, result)
        }

    @Test
    fun `typeCheck - flag enabled, checker passes`(): Unit =
        runBlocking {
            withThreadLocalCoroutineContext {
                val result = checkType(true, successCheckerExecutor)
                assertEquals(CheckerResult.Success, result.await())
            }
        }

    @Test
    fun `typeCheck - flag enabled, checker fails`(): Unit =
        runBlocking {
            withThreadLocalCoroutineContext {
                val result = checkType(true, errorCheckerExecutor)
                val error = result.await()?.asError?.error
                assertTrue(error is IllegalAccessException)
                assertEquals("denied", error.message)
            }
        }

    @Test
    fun `combineWithTypeCheck - scalar field`() {
        val result = runner.combineWithTypeCheck(
            createMockExecutionParameters(mockk<EngineExecutionContextImpl>()),
            mockSupplier,
            Value.fromValue(CheckerResult.Success),
            mockk<GraphQLScalarType>(),
            Value.fromValue(mockk<FieldResolutionResult>()),
            mockk(),
        )
        assertEquals(Value.fromValue(CheckerResult.Success), result)
    }

    @Test
    fun `combineWithTypeCheck - no type check`() {
        val engineExecutionContext = ContextMocks(
            myDispatcherRegistry = DispatcherRegistry(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        ).engineExecutionContext as EngineExecutionContextImpl
        val result = runner.combineWithTypeCheck(
            createMockExecutionParameters(engineExecutionContext),
            mockSupplier,
            Value.fromValue(CheckerResult.Success),
            fooObjectType,
            Value.fromValue(mockk<FieldResolutionResult>()),
            mockk(),
        )
        assertEquals(Value.fromValue(CheckerResult.Success), result)
    }

    @Test
    fun `combineWithTypeCheck - has type check`(): Unit =
        runBlocking {
            withThreadLocalCoroutineContext {
                val frr = FieldResolutionResult(
                    engineResult = objectEngineResult {
                        type = fooObjectType
                        data = emptyMap()
                    },
                    emptyList(),
                    ContextMocks().localContext,
                    emptyMap(),
                    null
                )
                val typeChecks = mapOf("Foo" to CheckerDispatcherImpl(errorCheckerExecutor))
                val engineExecutionContext = ContextMocks(
                    myDispatcherRegistry = DispatcherRegistry(emptyMap(), emptyMap(), emptyMap(), typeChecks)
                ).engineExecutionContext as EngineExecutionContextImpl
                val result = runner.combineWithTypeCheck(
                    createMockExecutionParameters(engineExecutionContext),
                    mockSupplier,
                    Value.fromValue(CheckerResult.Success),
                    mockk<GraphQLInterfaceType>(),
                    Value.fromValue(frr),
                    mockk(),
                )
                val error = result.await()?.asError?.error
                assertTrue(error is IllegalAccessException)
                assertEquals("denied", error.message)
            }
        }

    @Test
    fun `combineWithTypeCheck - has type check but raw value is null`(): Unit =
        runBlocking {
            withThreadLocalCoroutineContext {
                val frr = FieldResolutionResult(
                    engineResult = null,
                    emptyList(),
                    ContextMocks().localContext,
                    emptyMap(),
                    null
                )
                val typeChecks = mapOf("Foo" to CheckerDispatcherImpl(errorCheckerExecutor))
                val engineExecutionContext = ContextMocks(
                    myDispatcherRegistry = DispatcherRegistry(emptyMap(), emptyMap(), emptyMap(), typeChecks)
                ).engineExecutionContext as EngineExecutionContextImpl
                val result = runner.combineWithTypeCheck(
                    createMockExecutionParameters(engineExecutionContext),
                    mockSupplier,
                    Value.fromValue(CheckerResult.Success),
                    mockk<GraphQLInterfaceType>(),
                    Value.fromValue(frr),
                    mockk(),
                )
                assertEquals(CheckerResult.Success, result.await())
            }
        }

    private fun checkType(
        isEnabled: Boolean,
        checker: CheckerExecutor? = null
    ): Value<out CheckerResult?> {
        val checkerDispatchers = if (checker != null) mapOf("Foo" to CheckerDispatcherImpl(checker)) else emptyMap()
        val registry = DispatcherRegistry(emptyMap(), emptyMap(), emptyMap(), checkerDispatchers)
        val engineExecutionContext = mockk<EngineExecutionContextImpl> {
            every { dispatcherRegistry } returns registry
            every { rawSelectionSetFactory.rawSelectionSet(any(), any()) } returns RawSelectionSet.empty("Foo")
            every { copy(any(), any()) } returns this
            every { executeAccessChecksInModstrat } returns isEnabled
        }
        val oer = objectEngineResult {
            type = mockk { every { name } returns "Foo" }
            data = emptyMap()
        }
        val params = createMockExecutionParameters(engineExecutionContext)
        return runner.typeCheck(
            params,
            mockSupplier,
            oer,
            mockk(),
            mockk()
        )
    }

    private fun checkField(
        isEnabled: Boolean,
        checker: CheckerExecutor? = null
    ): Value<out CheckerResult?> {
        val exec = AccessCheckRunner(DefaultCoroutineInterop)
        val checkerDispatchers = if (checker != null) mapOf("Foo" to "bar" to CheckerDispatcherImpl(checker)) else emptyMap()
        val registry = DispatcherRegistry(emptyMap(), emptyMap(), checkerDispatchers, emptyMap())
        val context = ContextMocks(
            myEngineExecutionContext = mockk<EngineExecutionContextImpl> {
                every { dispatcherRegistry } returns registry
                every { rawSelectionSetFactory.rawSelectionSet(any(), any()) } returns RawSelectionSet.empty("Foo")
                every { copy(any(), any()) } returns this
                every { executeAccessChecksInModstrat } returns isEnabled
            }
        ).engineExecutionContext as? EngineExecutionContextImpl
        val params = createMockExecutionParameters(context)

        // Override field-check specific properties
        every { params.executionStepInfo } returns mockk {
            every { objectType.name } returns "Foo"
            every { arguments } returns mapOf()
        }
        every { params.field?.fieldName } returns "bar"
        every { params.parentEngineResult } returns mockk<ObjectEngineResultImpl>()
        val dataFetchingEnvironmentProvider = mockk<Supplier<DataFetchingEnvironment>> {
            every { get() } returns mockk()
        }
        return exec.fieldCheck(params, dataFetchingEnvironmentProvider)
    }

    private fun createMockExecutionParameters(engineExecutionContext: EngineExecutionContextImpl?): ExecutionParameters {
        return mockk<ExecutionParameters> {
            every { instrumentation } returns mockk {
                every { instrumentAccessCheck(any(), any(), any()) } answers { firstArg() }
            }
            every { executionContext } returns mockk {
                every { instrumentationState } returns mockk()
                engineExecutionContext?.let { every { getLocalContextForType<EngineExecutionContextImpl>() } returns it }
            }
            every { gjParameters } returns mockk()
            every { field } returns mockk {
                every { fieldTypeChildPlans } returns emptyMap()
            }
        }
    }

    companion object {
        private val fooObjectType = mockk<GraphQLObjectType> { every { name } returns "Foo" }
        private val successCheckerExecutor = object : CheckerExecutor {
            override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = mapOf()

            override suspend fun execute(
                arguments: Map<String, Any?>,
                objectDataMap: Map<String, EngineObjectData>,
                context: EngineExecutionContext,
                checkerType: CheckerExecutor.CheckerType
            ): CheckerResult {
                return CheckerResult.Success
            }
        }

        private val errorCheckerExecutor = object : CheckerExecutor {
            override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = mapOf()

            override suspend fun execute(
                arguments: Map<String, Any?>,
                objectDataMap: Map<String, EngineObjectData>,
                context: EngineExecutionContext,
                checkerType: CheckerExecutor.CheckerType
            ): CheckerResult {
                return object : CheckerResult.Error {
                    override val error: Exception = IllegalAccessException("denied")

                    override fun isErrorForResolver(ctx: CheckerResultContext) = true

                    override fun combine(fieldResult: CheckerResult.Error) = this
                }
            }
        }
    }
}
