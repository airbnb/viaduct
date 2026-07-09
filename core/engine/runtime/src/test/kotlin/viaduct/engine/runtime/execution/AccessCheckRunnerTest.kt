@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStepInfo
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Locale
import java.util.function.Supplier
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createEngineSelectionSet
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.runtime.CheckerDispatcher
import viaduct.engine.runtime.CheckerDispatcherImpl
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.EngineObjectDataFactory
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.IsResolverSelective
import viaduct.engine.runtime.QueryPlanExecutionCondition
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.context.getLocalContextForType
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime.objectEngineResult
import viaduct.graphql.utils.ParsedSelections

class AccessCheckRunnerTest {
    val runner = AccessCheckRunner(DefaultCoroutineInterop)
    private val emptyFooSelectionSet = createEngineSelectionSet(
        ParsedSelections.empty("Foo"),
        MockSchema.mk("type Foo { id: ID }"),
        emptyMap()
    )

    val mockSupplier = mockk<Supplier<DataFetchingEnvironment>>()
    val mockDataFetchingEnvironment = mockk<DataFetchingEnvironment>()

    @BeforeEach
    fun setUp() {
        every { mockSupplier.get() } returns mockDataFetchingEnvironment
    }

    @Test
    fun `fieldCheck - no checker`(): Unit =
        runBlocking {
            val result = checkField()
            assertEquals(Value.nullValue, result)
        }

    @Test
    fun `fieldCheck - checker passes`(): Unit =
        runBlocking {
            withThreadLocalCoroutineContext {
                val result = checkField(successCheckerExecutor)
                assertEquals(CheckerResult.Success, result.await())
            }
        }

    @Test
    fun `fieldCheck - checker fails`(): Unit =
        runBlocking {
            withThreadLocalCoroutineContext {
                val result = checkField(errorCheckerExecutor)
                val error = result.await()?.asError?.error
                assertTrue(error is IllegalAccessException)
                assertEquals("denied", error!!.message)
            }
        }

    @Test
    fun `fieldCheck skips checker RSS materialization when execution condition is false`(): Unit =
        runBlocking {
            withThreadLocalCoroutineContext {
                var conditionEvaluated = false
                var checkerExecuted = false
                val checker = object : CheckerExecutor {
                    override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = mapOf(
                        "checker" to createRSS(
                            "Foo",
                            "id",
                            forChecker = true,
                            executionCondition = QueryPlanExecutionCondition {
                                conditionEvaluated = true
                                false
                            }
                        )
                    )

                    override suspend fun execute(
                        arguments: Map<String, Any?>,
                        objectDataMap: Map<String, EngineObjectData>,
                        context: EngineExecutionContext,
                        checkerType: CheckerExecutor.CheckerType
                    ): CheckerResult {
                        checkerExecuted = true
                        assertEquals(emptyList<String>(), objectDataMap.getValue("checker").fetchSelections().toList())
                        return CheckerResult.Success
                    }
                }

                val result = checkField(checker, failIfSelectionSetMaterialized = true)

                assertEquals(CheckerResult.Success, result.await())
                assertEquals(true, conditionEvaluated)
                assertEquals(true, checkerExecuted)
            }
        }

    @Test
    fun `fieldCheck materializes checker data inside instrumentAccessCheck`() =
        runBlocking {
            withThreadLocalCoroutineContext {
                var insideAccessCheck = false
                var materializedInsideAccessCheck = false
                val dispatcher = object : CheckerDispatcher {
                    override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = mapOf("checker" to null)
                    override val executor: CheckerExecutor = object : CheckerExecutor {
                        override val requiredSelectionSets = mapOf("checker" to null)

                        override suspend fun execute(
                            arguments: Map<String, Any?>,
                            objectDataMap: Map<String, EngineObjectData>,
                            context: EngineExecutionContext,
                            checkerType: CheckerExecutor.CheckerType
                        ): CheckerResult = CheckerResult.Success
                    }

                    override suspend fun execute(
                        arguments: Map<String, Any?>,
                        objectDataFactories: Map<String, EngineObjectDataFactory>,
                        context: EngineExecutionContext,
                        checkerType: CheckerExecutor.CheckerType
                    ): CheckerResult {
                        objectDataFactories.getValue("checker").create(null)
                        materializedInsideAccessCheck = insideAccessCheck
                        return CheckerResult.Success
                    }
                }
                val registry = DispatcherRegistry.Impl(emptyMap(), emptyMap(), mapOf("Foo" to "bar" to dispatcher), emptyMap())
                val context = ContextMocks(
                    myEngineExecutionContext = mockk<EngineExecutionContextImpl> {
                        every { impl } returns this
                        every { dispatcherRegistry } returns registry
                        every { activeSchema } returns mockk()
                        every { fieldScopeSupplier } returns mockk()
                        every { dataFetchingEnvironment } returns null
                        every { copy(any(), any(), any(), any(), any()) } returns this
                        every { selectiveOERKeysEnabled } returns false
                        every { isResolverSelective } returns IsResolverSelective.Never
                    }
                ).engineExecutionContext as? EngineExecutionContextImpl
                val params = createMockExecutionParameters(context)
                every { params.instrumentation } returns object : ViaductModernGJInstrumentation {
                    override fun instrumentAccessCheck(
                        checkerExecutor: CheckerExecutor,
                        dataFetchingEnvironment: DataFetchingEnvironment,
                        parameters: InstrumentationExecutionStrategyParameters,
                        state: InstrumentationState?
                    ): CheckerExecutor =
                        object : CheckerExecutor by checkerExecutor {
                            override suspend fun execute(
                                arguments: Map<String, Any?>,
                                objectDataMap: Map<String, EngineObjectData>,
                                context: EngineExecutionContext,
                                checkerType: CheckerExecutor.CheckerType
                            ): CheckerResult {
                                insideAccessCheck = true
                                return try {
                                    checkerExecutor.execute(arguments, objectDataMap, context, checkerType)
                                } finally {
                                    insideAccessCheck = false
                                }
                            }
                        }
                }
                every { params.executionStepInfo } returns ExecutionStepInfo.newExecutionStepInfo()
                    .type(fooObjectType)
                    .fieldContainer(fooObjectType)
                    .build()
                every { params.field } returns mockk {
                    every { fieldName } returns "bar"
                    every { childPlans } returns emptyList()
                    every { fieldTypeChildPlans } returns FieldTypeChildPlans.empty
                }
                every { params.currentObjectEngineResult } returns objectEngineResult {
                    type = fooObjectType
                    data = emptyMap()
                }
                val dataFetchingEnvironmentProvider = mockk<Supplier<DataFetchingEnvironment>> {
                    every { get() } returns mockk()
                }

                val result = runner.fieldCheck(params, dataFetchingEnvironmentProvider)

                assertEquals(CheckerResult.Success, result.await())
                assertEquals(true, materializedInsideAccessCheck)
            }
        }

    @Test
    fun `typeCheck - no checker`(): Unit =
        runBlocking {
            val result = checkType()
            assertEquals(Value.nullValue, result)
        }

    @Test
    fun `typeCheck - checker passes`(): Unit =
        runBlocking {
            withThreadLocalCoroutineContext {
                val result = checkType(successCheckerExecutor)
                assertEquals(CheckerResult.Success, result.await())
            }
        }

    @Test
    fun `typeCheck - checker fails`(): Unit =
        runBlocking {
            withThreadLocalCoroutineContext {
                val result = checkType(errorCheckerExecutor)
                val error = result.await()?.asError?.error
                assertTrue(error is IllegalAccessException)
                assertEquals("denied", error!!.message)
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
            myDispatcherRegistry = DispatcherRegistry.Impl(emptyMap(), emptyMap(), emptyMap(), emptyMap())
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
                    myDispatcherRegistry = DispatcherRegistry.Impl(emptyMap(), emptyMap(), emptyMap(), typeChecks)
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
                assertEquals("denied", error!!.message)
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
                    myDispatcherRegistry = DispatcherRegistry.Impl(emptyMap(), emptyMap(), emptyMap(), typeChecks)
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

    @Test
    fun `typeCheck - delegates child plan launching to launchQueryPlan`(): Unit =
        runBlocking {
            withThreadLocalCoroutineContext {
                val childPlanLaunched = checkTypeWithExecutionCondition(QueryPlanExecutionCondition.ALWAYS_EXECUTE)

                assertEquals(true, childPlanLaunched, "Child plan should be launched via launchQueryPlan")
            }
        }

    @Test
    fun `typeCheck augments parameters before launching field type child plans`(): Unit =
        runBlocking {
            withThreadLocalCoroutineContext {
                val mockChildPlan = mockk<QueryPlan> {
                    every { executionCondition } returns QueryPlanExecutionCondition.ALWAYS_EXECUTE
                    every { selectionSet } returns mockk(relaxed = true)
                    every { requiredSelectionSetId } returns null
                    every { childPlanIds } returns emptyList()
                }
                val typeChecks = mapOf("Foo" to CheckerDispatcherImpl(successCheckerExecutor))
                val registry = DispatcherRegistry.Impl(emptyMap(), emptyMap(), emptyMap(), typeChecks)
                val engineExecutionContext = mockk<EngineExecutionContextImpl> {
                    every { impl } returns this
                    every { dispatcherRegistry } returns registry
                    every { engineSelectionSetFactory.engineSelectionSet(any(), any()) } returns emptyFooSelectionSet
                    every { activeSchema } returns mockk()
                    every { fieldScopeSupplier } returns mockk()
                    every { dataFetchingEnvironment } returns null
                    every { copy(any(), any(), any(), any(), any()) } returns this
                    every { selectiveOERKeysEnabled } returns false
                    every { isResolverSelective } returns IsResolverSelective.Never
                }
                val params = createMockExecutionParameters(engineExecutionContext)
                val typeCheckParameters = createMockExecutionParameters(engineExecutionContext)
                val childPlanRss = createRSS("Foo", "id")
                val overrideQueryPlanIndex = QueryPlanIndex.single(childPlanRss.id, mockChildPlan)
                val augmentedQueryPlanIndex = slot<QueryPlanIndex>()
                every { mockChildPlan.index } returns overrideQueryPlanIndex
                every { params.field } returns mockk {
                    every { fieldName } returns "testField"
                    every { fieldTypeChildPlans } returns fieldTypeChildPlansFor(fooObjectType to listOf(mockChildPlan))
                }
                every { params.queryPlanIndex } returns QueryPlanIndex.empty()
                every { params.withQueryPlanIndex(capture(augmentedQueryPlanIndex)) } returns typeCheckParameters
                val fieldResolver = mockk<FieldResolver> {
                    every { launchQueryPlan(any(), any(), any(), any(), any()) } just Runs
                }
                val oer = objectEngineResult {
                    type = fooObjectType
                    data = emptyMap()
                }
                val fieldResolutionResult = FieldResolutionResult(
                    engineResult = oer,
                    emptyList(),
                    ContextMocks().localContext,
                    emptyMap(),
                    null,
                )

                val result = runner.typeCheck(
                    params,
                    mockSupplier,
                    oer,
                    fieldResolutionResult,
                    fieldResolver,
                )

                assertSame(CheckerResult.Success, result.await())
                assertSame(mockChildPlan, augmentedQueryPlanIndex.captured.find(childPlanRss.id))
                verify {
                    fieldResolver.launchQueryPlan(
                        typeCheckParameters,
                        mockChildPlan,
                        mockDataFetchingEnvironment,
                        any(),
                        emptySet<RequiredSelectionSet.Id>(),
                    )
                }
            }
        }

    private suspend fun checkTypeWithExecutionCondition(executionCondition: QueryPlanExecutionCondition): Boolean {
        var childPlanLaunched = false
        val mockChildPlan = mockk<QueryPlan> {
            every { this@mockk.executionCondition } returns executionCondition
            every { selectionSet } returns mockk(relaxed = true)
            every { requiredSelectionSetId } returns null
            every { childPlanIds } returns emptyList()
        }
        val baseQueryPlanIndex: QueryPlanIndex = QueryPlanIndex.empty()
        val overrideQueryPlanIndex = QueryPlanIndex.single(createRSS("Foo", "id").id, mockChildPlan)
        every { mockChildPlan.index } returns overrideQueryPlanIndex

        val typeChecks = mapOf("Foo" to CheckerDispatcherImpl(successCheckerExecutor))
        val registry = DispatcherRegistry.Impl(emptyMap(), emptyMap(), emptyMap(), typeChecks)
        val engineExecutionContext = mockk<EngineExecutionContextImpl> {
            every { impl } returns this
            every { dispatcherRegistry } returns registry
            every { engineSelectionSetFactory.engineSelectionSet(any(), any()) } returns emptyFooSelectionSet
            every { activeSchema } returns mockk()
            every { fieldScopeSupplier } returns mockk()
            every { dataFetchingEnvironment } returns null
            every { copy(any(), any(), any(), any(), any()) } returns this
            every { selectiveOERKeysEnabled } returns false
            every { isResolverSelective } returns IsResolverSelective.Never
        }
        val oer = objectEngineResult {
            type = fooObjectType
            data = emptyMap()
        }
        val fieldResolutionResult = FieldResolutionResult(
            engineResult = oer,
            emptyList(),
            ContextMocks().localContext,
            emptyMap(),
            null,
        )
        val params = mockk<ExecutionParameters> {
            every { this@mockk.engineExecutionContext } returns engineExecutionContext
            every { queryPlan } returns mockk {
                every { index } returns baseQueryPlanIndex
            }
            every { queryPlanIndex } returns baseQueryPlanIndex
            every { constants } returns createMockConstants()
            stubCopyWithAnyQueryPlanIndex(this@mockk, this@mockk)
            every { withQueryPlanIndex(any()) } returns this@mockk
            every { instrumentation } returns mockk {
                every { instrumentAccessCheck(any(), any(), any(), any()) } answers { firstArg() }
            }
            every { executionContext } returns mockk {
                every { instrumentationState } returns mockk()
            }
            every { executionContextWithLocalContext } returns mockk {
                every { instrumentationState } returns mockk()
                every { getLocalContextForType<EngineExecutionContextImpl>() } returns engineExecutionContext
            }
            every { localContext } returns mockk {
                every { get<EngineExecutionContextImpl>() } returns engineExecutionContext
            }
            every { gjParameters } returns mockk()
            every { field } returns mockk {
                every { fieldName } returns "testField"
                every { fieldTypeChildPlans } returns fieldTypeChildPlansFor(fooObjectType to listOf(mockChildPlan))
            }
            every { launchOnRootScope(any()) } answers {
                childPlanLaunched = true
                mockk<Job>(relaxed = true)
            }
        }
        val fieldResolver = FieldResolver(runner)

        val result = runner.typeCheck(
            params,
            mockSupplier,
            oer,
            fieldResolutionResult,
            fieldResolver
        )

        result.await()
        return childPlanLaunched
    }

    private fun checkType(checker: CheckerExecutor? = null): Value<out CheckerResult?> {
        val checkerDispatchers = if (checker != null) mapOf("Foo" to CheckerDispatcherImpl(checker)) else emptyMap()
        val registry = DispatcherRegistry.Impl(emptyMap(), emptyMap(), emptyMap(), checkerDispatchers)
        val engineExecutionContext = mockk<EngineExecutionContextImpl> {
            every { impl } returns this
            every { dispatcherRegistry } returns registry
            every { engineSelectionSetFactory.engineSelectionSet(any(), any()) } returns emptyFooSelectionSet
            every { activeSchema } returns mockk()
            every { fieldScopeSupplier } returns mockk()
            every { dataFetchingEnvironment } returns null
            every { copy(any(), any(), any(), any(), any()) } returns this
            every { selectiveOERKeysEnabled } returns false
            every { isResolverSelective } returns IsResolverSelective.Never
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
        checker: CheckerExecutor? = null,
        failIfSelectionSetMaterialized: Boolean = false,
    ): Value<out CheckerResult?> {
        val exec = AccessCheckRunner(DefaultCoroutineInterop)
        val checkerDispatchers = if (checker != null) mapOf("Foo" to "bar" to CheckerDispatcherImpl(checker)) else emptyMap()
        val registry = DispatcherRegistry.Impl(emptyMap(), emptyMap(), checkerDispatchers, emptyMap())
        val context = ContextMocks(
            myEngineExecutionContext = mockk<EngineExecutionContextImpl> {
                every { impl } returns this
                every { dispatcherRegistry } returns registry
                every { engineSelectionSetFactory.engineSelectionSet(any(), any()) } answers {
                    if (failIfSelectionSetMaterialized) {
                        error("Checker RSS should not be materialized when its execution condition is false")
                    }
                    emptyFooSelectionSet
                }
                every { activeSchema } returns mockk()
                every { fieldScopeSupplier } returns mockk()
                every { dataFetchingEnvironment } returns null
                every { copy(any(), any(), any(), any(), any()) } returns this
                every { selectiveOERKeysEnabled } returns false
                every { isResolverSelective } returns IsResolverSelective.Never
            }
        ).engineExecutionContext as? EngineExecutionContextImpl
        val params = createMockExecutionParameters(context)

        // Override field-check specific properties
        every { params.executionStepInfo } returns ExecutionStepInfo.newExecutionStepInfo()
            .type(fooObjectType)
            .fieldContainer(fooObjectType)
            .build()
        every { params.field } returns mockk {
            every { fieldName } returns "bar"
            every { childPlans } returns emptyList()
            every { fieldTypeChildPlans } returns FieldTypeChildPlans.empty
        }
        every { params.currentObjectEngineResult } returns objectEngineResult {
            type = fooObjectType
            data = emptyMap()
        }
        val dataFetchingEnvironmentProvider = mockk<Supplier<DataFetchingEnvironment>> {
            every { get() } returns mockk()
        }
        return exec.fieldCheck(params, dataFetchingEnvironmentProvider)
    }

    private fun createMockExecutionParameters(engineExecutionContext: EngineExecutionContextImpl?): ExecutionParameters {
        return mockk<ExecutionParameters> {
            engineExecutionContext?.let { every { this@mockk.engineExecutionContext } returns it }
            every { instrumentation } returns mockk {
                every { instrumentAccessCheck(any(), any(), any(), any()) } answers { firstArg() }
            }
            every { executionContext } returns mockk<ExecutionContext> {
                every { instrumentationState } returns mockk()
                every { graphQLContext } returns GraphQLContext.getDefault()
                every { locale } returns Locale.US
            }
            every { executionContextWithLocalContext } returns mockk {
                every { instrumentationState } returns mockk()
                engineExecutionContext?.let { every { getLocalContextForType<EngineExecutionContextImpl>() } returns it }
            }
            every { localContext } returns mockk {
                engineExecutionContext?.let { every { get<EngineExecutionContextImpl>() } returns it }
            }
            every { gjParameters } returns mockk()
            every { graphQLSchema } returns mockk<GraphQLSchema> {
                every { queryType.name } returns "Query"
            }
            every { queryEngineResult } returns mockk()
            every { queryPlan } returns mockk {
                every { index } returns QueryPlanIndex.empty()
            }
            every { queryPlanIndex } returns mockk()
            every { constants } returns createMockConstants()
            stubCopyWithAnyQueryPlanIndex(this@mockk, this@mockk)
            every { field } returns mockk {
                every { childPlans } returns emptyList()
                every { fieldTypeChildPlans } returns FieldTypeChildPlans.empty
            }
        }
    }

    private fun fieldTypeChildPlansFor(vararg entries: Pair<GraphQLObjectType, List<QueryPlan>>): FieldTypeChildPlans {
        val plansByType = entries.toMap()
        return FieldTypeChildPlans { objectType -> plansByType[objectType].orEmpty() }
    }

    private fun createMockConstants(): ExecutionParameters.Constants {
        val executionContext = mockk<ExecutionContext> {
            every { instrumentation } returns mockk<ViaductModernGJInstrumentation>(relaxed = true)
        }
        return ExecutionParameters.Constants(
            executionContext = executionContext,
            rootEngineResult = mockk(relaxed = true),
            supervisorScopeFactory = { CoroutineScope(it) },
            rootCoroutineContext = EmptyCoroutineContext,
        )
    }

    private fun stubCopyWithAnyQueryPlanIndex(
        parameters: ExecutionParameters,
        result: ExecutionParameters
    ) {
        every {
            parameters.copy(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns result
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
