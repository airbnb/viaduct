@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStepInfo
import graphql.execution.MergedField
import graphql.execution.ResultPath
import graphql.execution.values.InputInterceptor
import graphql.execution.values.legacycoercing.LegacyCoercingInputInterceptor
import graphql.language.Field as GJField
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import java.util.concurrent.CompletionException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.Caller
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineObjectDataBuilder
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.instrumentation.ViaductTenantNameContext
import viaduct.engine.api.mocks.FieldUnbatchedResolverFn
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createEngineSelectionSet
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.runtime.EngineExecutionContextExtensions.setExecutionHandle
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.ObjectRootFieldReference
import viaduct.engine.runtime.QueryPlanExecutionCondition
import viaduct.engine.runtime.SyncProxyEngineObjectData
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.context.getLocalContextForType
import viaduct.engine.runtime.createSchema
import viaduct.engine.runtime.dfe.ViaductDataFetchingEnvironment
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime.select.EngineSelectionSetImpl
import viaduct.engine.runtime.select.ProjectedEngineSelectionSet
import viaduct.graphql.utils.ParsedSelections
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

@OptIn(ExperimentalCoroutinesApi::class)
class ResolverDataFetcherTest {
    // Access checks always run in the modern execution strategy; flag state no longer affects this.
    private val allDisabledFlags = MockFlagManager()
    private val allFlagSets = listOf(
        allDisabledFlags,
    )

    private class Fixture(
        val expectedResult: Any?,
        val requiredSelectionSet: RequiredSelectionSet?,
        val querySelectionSet: RequiredSelectionSet? = null,
        val flagManager: FlagManager,
        val resolveWithException: Boolean = false,
        val testType: String = "TestType",
        val testField: String = "testField",
        val testFieldType: String = "String",
        val tenantNameResolver: TenantNameResolver = TenantNameResolver(),
        val resolverFn: FieldUnbatchedResolverFn? = null,
    ) {
        val schema: ViaductSchema = createSchema(
            """
            type Query { placeholder(arg:Int): Int }
            type $testType {
                $testField(id:Int): $testFieldType
                y: Int
                himejiId: String
                foo: Foo
                bar(id:Int): Bar
                baz(id:Int): Baz
            }
            type Foo { bar: Bar }
            type Bar { x: Int }
            type Baz { x: Int }
            """.trimIndent()
        )
        val testTypeObject: GraphQLObjectType = schema.schema.getObjectType(testType)
        val executionStepInfo: ExecutionStepInfo? = ExecutionStepInfo.newExecutionStepInfo()
            .type(schema.schema.getTypeAs(testFieldType))
            .fieldDefinition(testTypeObject.getField(testField))
            .fieldContainer(testTypeObject)
            .path(ResultPath.parse("/$testField"))
            .build()
        var resolverRan = false
        var lastReceivedObjectValue: Any? = null
        var lastReceivedSelectionSet: EngineSelectionSet? = null
        var capturedTenantContext: ViaductTenantNameContext? = null
        val resolverId = "$testType.$testField"
        val objectValue = EngineObjectDataBuilder.from(testTypeObject).put(testField, expectedResult).build()
        val executor = if (resolveWithException) {
            TestFieldUnbatchedResolverExecutor(
                objectSelectionSet = requiredSelectionSet,
                querySelectionSet = querySelectionSet,
                resolverId = resolverId,
                unbatchedResolveFn = { _, _, _, _, _ -> throw RuntimeException("test MockResolverExecutor") },
            )
        } else {
            TestFieldUnbatchedResolverExecutor(
                objectSelectionSet = requiredSelectionSet,
                querySelectionSet = querySelectionSet,
                resolverId = resolverId,
                unbatchedResolveFn = resolverFn ?: { _, receivedObjectValue, _, receivedSelectionSet, _ ->
                    resolverRan = true
                    lastReceivedObjectValue = receivedObjectValue
                    lastReceivedSelectionSet = receivedSelectionSet
                    capturedTenantContext = ViaductTenantNameContext.getCurrent()
                    expectedResult
                },
            )
        }
        val resolverDataFetcher = ResolverDataFetcher(
            typeName = testType,
            fieldName = testField,
            fieldResolverDispatcher = FieldResolverDispatcherImpl(executor),
            tenantNameResolver = tenantNameResolver,
        )

        // GraphQL-Java calls are stubbed on this delegate. The wrapper around it is real, so that
        // its init establishes the context -> DFE link the way production does.
        val delegateDataFetchingEnvironment: DataFetchingEnvironment = mockk()
        val engineResultLocalContext = EngineResultLocalContext(
            rootEngineResult = ObjectEngineResultImpl.newForType(schema.schema.queryType),
            currentObjectEngineResult = ObjectEngineResultImpl.newForType(testTypeObject),
            queryEngineResult = ObjectEngineResultImpl.newForType(schema.schema.queryType),
            executionStrategyParams = null,
            executionContext = null
        )
        private val baseEngineExecutionContextImpl = ContextMocks(
            myFullSchema = schema,
            myFlagManager = flagManager,
        ).engineExecutionContextImpl

        private val queryPlanParameters = QueryPlan.Parameters(
            schema = schema,
            registry = baseEngineExecutionContextImpl.dispatcherRegistry,
            dispatcherRegistry = baseEngineExecutionContextImpl.dispatcherRegistry,
        )
        private val currentField = QueryPlan.CollectedField(
            responseKey = testField,
            selectionSet = null,
            mergedField = MergedField.newMergedField()
                .addField(GJField.newField(testField).build())
                .build(),
            childPlans = emptyList(),
            fieldTypeChildPlans = FieldTypeChildPlans.empty,
        )
        private val currentQueryPlan = QueryPlan(
            selectionSet = QueryPlan.SelectionSet(testTypeObject, currentField),
            fragments = QueryPlan.Fragments.empty,
            variablesResolvers = emptyList(),
            childPlanIds = emptyList(),
            baseIndex = QueryPlanIndex.empty(),
            executionCondition = QueryPlanExecutionCondition.ALWAYS_EXECUTE,
            variableDefinitions = emptyList(),
        )

        private fun allRequiredSelectionSets(rss: RequiredSelectionSet): List<RequiredSelectionSet> =
            listOf(rss) + rss.variablesResolvers.flatMap { variablesResolver ->
                variablesResolver.requiredSelectionSet?.let(::allRequiredSelectionSets).orEmpty()
            }

        private val indexedRssPlans = listOfNotNull(requiredSelectionSet, querySelectionSet)
            .flatMap(::allRequiredSelectionSets)
            .associate { rss ->
                rss.id to runBlocking {
                    QueryPlanFactory.Default.buildFromRequiredSelectionSet(
                        parameters = queryPlanParameters,
                        rss = rss,
                    )
                }
            }
        private val queryPlanIndex: QueryPlanIndex =
            indexedRssPlans.entries.fold(QueryPlanIndex.empty()) { index, (id, plan) ->
                index + QueryPlanIndex.single(id, plan) + plan.index
            }

        private val executionConstants = ExecutionParameters.Constants(
            executionContext = mockk<ExecutionContext>(relaxed = true),
            rootEngineResult = engineResultLocalContext.rootEngineResult,
            supervisorScopeFactory = { CoroutineScope(it) },
            rootCoroutineContext = EmptyCoroutineContext,
        )
        private val executionHandle = mockk<ExecutionParameters>()
        val engineExecutionContextImpl = baseEngineExecutionContextImpl.also {
            every { executionHandle.constants } returns executionConstants
            every { executionHandle.queryPlanIndex } returns queryPlanIndex
            every { executionHandle.queryPlan } returns currentQueryPlan
            every { executionHandle.coercedVariables } returns CoercedVariables.emptyVariables()
            every { executionHandle.executionContext } returns executionConstants.executionContext
            every { executionHandle.executionStepInfo } returns executionStepInfo!!
            every { executionHandle.engineExecutionContext } returns it
            every { executionHandle.field } returns currentField
            it.setExecutionHandle(executionHandle)
        }

        // Production hands the wrapper its own copy of the context, so mirror that here.
        val dataFetchingEnvironment: ViaductDataFetchingEnvironment =
            ViaductDataFetchingEnvironmentImpl(delegateDataFetchingEnvironment, engineExecutionContextImpl.copy())

        init {
            every { delegateDataFetchingEnvironment.graphQLSchema } returns schema.schema
            every { delegateDataFetchingEnvironment.arguments } returns mapOf("arg1" to "param1")
            every { delegateDataFetchingEnvironment.fieldDefinition } returns testTypeObject.getField(testField)
            every { delegateDataFetchingEnvironment.executionStepInfo } returns executionStepInfo
            every { delegateDataFetchingEnvironment.getLocalContextForType<EngineResultLocalContext>() } returns (engineResultLocalContext)

            // define local var to get around naming collision issue
            every { delegateDataFetchingEnvironment.getLocalContextForType<EngineExecutionContextImpl>() } returns (engineExecutionContextImpl)
            every { delegateDataFetchingEnvironment.getSource<Any>() } returns mockk()
            every { delegateDataFetchingEnvironment.graphQlContext } returns GraphQLContext.newContext()
                .of(InputInterceptor::class.java, LegacyCoercingInputInterceptor.migratesValues())
                .build()
            every { delegateDataFetchingEnvironment.locale } returns Locale.US
        }
    }

    @Test
    fun `test resolving with null objectSelectionSet`(): Unit =
        runBlocking(Dispatchers.Default) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                ).apply {
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)

                    // verify that localContext has dataFetchingEnvironment copied
                    assertEquals(dataFetchingEnvironment, executor.lastReceivedLocalContext?.dataFetchingEnvironment)
                }
            }
        }

    @Test
    fun `test resolving with existing object selection set`(): Unit =
        runBlocking(Dispatchers.Default) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = RequiredSelectionSet(
                        SelectionsParser.parse("TestType", "testField"),
                        emptyList(),
                        forChecker = false
                    ),
                    flagManager = allDisabledFlags
                ).apply {
                    engineResultLocalContext.currentObjectEngineResult.putResolvedValue("testField", expectedResult)

                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)

                    // verify that localContext has dataFetchingEnvironment copied
                    assertEquals(dataFetchingEnvironment, executor.lastReceivedLocalContext?.dataFetchingEnvironment)
                }
            }
        }

    @Test
    fun `test sync value getter passes SyncProxyEngineObjectData as objectValue to resolver`(): Unit =
        runBlocking(Dispatchers.Default) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = RequiredSelectionSet(
                        SelectionsParser.parse("TestType", "testField"),
                        emptyList(),
                        forChecker = false
                    ),
                    flagManager = allDisabledFlags,
                ).apply {
                    // Populate the parent engine result so sync resolution can complete
                    engineResultLocalContext.currentObjectEngineResult.computeIfAbsent(
                        ObjectEngineResult.Key("testField", "testField", emptyMap())
                    ) { setter ->
                        setter.set(
                            ObjectEngineResultImpl.RAW_VALUE_SLOT,
                            Value.fromValue(
                                FieldResolutionResult(
                                    engineResult = expectedResult,
                                    errors = emptyList(),
                                    localContext = CompositeLocalContext.empty,
                                    extensions = emptyMap(),
                                    originalSource = null
                                )
                            )
                        )
                        setter.set(ObjectEngineResultImpl.ACCESS_CHECK_SLOT, Value.fromValue(null))
                    }

                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)
                    assertTrue(resolverRan)
                    assertTrue(lastReceivedObjectValue is SyncProxyEngineObjectData)
                }
            }
        }

    @Test
    fun `resolver receives EngineSelectionSetImpl when mat resolution is disabled`(): Unit =
        runBlocking(Dispatchers.Default) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    testFieldType = "Foo",
                ).apply {
                    resolverDataFetcher.get(dataFetchingEnvironment).join()

                    assertTrue(lastReceivedSelectionSet is EngineSelectionSetImpl)
                }
            }
        }

    @Test
    fun `resolver receives ExecutionSelectionSet when mat resolution is enabled`(): Unit =
        runBlocking(Dispatchers.Default) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION),
                    testFieldType = "Foo",
                ).apply {
                    resolverDataFetcher.get(dataFetchingEnvironment).join()

                    assertTrue(lastReceivedSelectionSet is ExecutionSelectionSet)
                }
            }
        }

    @Test
    fun `test resolving required selections with FromArgument variables -- all flag configurations`(): Unit =
        runBlocking(Dispatchers.Default) {
            withThreadLocalCoroutineContext {
                for (flags in allFlagSets) {
                    Fixture(
                        expectedResult = "test fetched result",
                        requiredSelectionSet = SelectionsParser.parse("TestType", "baz(id:\$myid) { x } ")
                            .let { parsedSelections ->
                                RequiredSelectionSet(
                                    selections = parsedSelections,
                                    VariablesResolver.fromSelectionSetVariables(
                                        parsedSelections,
                                        querySelections = ParsedSelections.empty("Query"),
                                        forChecker = false,
                                        variables = listOf(
                                            FromArgumentVariable("myid", "id")
                                        )
                                    ),
                                    forChecker = false
                                )
                            },
                        flagManager = flags
                    ).apply {
                        every { delegateDataFetchingEnvironment.arguments } returns mapOf("id" to 1)
                        val bazResult = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Baz")!!)
                        bazResult.putResolvedInt("x", 123)
                        engineResultLocalContext.currentObjectEngineResult.putResolvedObject(
                            fieldName = "baz",
                            value = bazResult,
                            arguments = mapOf("id" to 1),
                        )

                        val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                        assertEquals(expectedResult, receivedResult)

                        // verify that localContext has dataFetchingEnvironment copied
                        assertEquals(dataFetchingEnvironment, executor.lastReceivedLocalContext?.dataFetchingEnvironment)
                    }
                }
            }
        }

    @Test
    fun `test resolver exception propagation`(): Unit =
        runBlocking(Dispatchers.Default) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = null,
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    resolveWithException = true
                ).apply {
                    val e = assertThrows<CompletionException> {
                        resolverDataFetcher.get(dataFetchingEnvironment).join()
                    }
                    assertTrue(e.cause is RuntimeException)
                }
            }
        }

    @Test
    fun `tenant name context is set during resolver execution`(): Unit =
        runBlocking(Dispatchers.Default) {
            withThreadLocalCoroutineContext {
                val testTenantNameResolver = object : TenantNameResolver() {
                    override fun resolve(
                        typeName: String,
                        fieldName: String
                    ) = "test-tenant"
                }
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    tenantNameResolver = testTenantNameResolver,
                ).apply {
                    resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals("test-tenant", capturedTenantContext?.tenantName)
                }
            }
        }

    @Test
    fun `resolver context records the current field and tenant`(): Unit =
        runBlocking(Dispatchers.Default) {
            withThreadLocalCoroutineContext {
                val testTenantNameResolver = object : TenantNameResolver() {
                    override fun resolve(
                        typeName: String,
                        fieldName: String
                    ) = "test-tenant"
                }
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    tenantNameResolver = testTenantNameResolver,
                ).apply {
                    resolverDataFetcher.get(dataFetchingEnvironment).join()

                    val reference = executor.lastReceivedLocalContext!!.createRootFieldReference(
                        listOf("test"),
                        testTypeObject,
                        emptyMap(),
                    )
                    assertEquals(
                        Caller("test-tenant", testType, testField),
                        (reference as ObjectRootFieldReference).caller,
                    )
                }
            }
        }

    @Test
    fun `tenant name context does not leak after resolver execution`(): Unit =
        runBlocking(Dispatchers.Default) {
            withThreadLocalCoroutineContext {
                val testTenantNameResolver = object : TenantNameResolver() {
                    override fun resolve(
                        typeName: String,
                        fieldName: String
                    ) = "test-tenant"
                }
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    tenantNameResolver = testTenantNameResolver,
                ).apply {
                    resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertNull(ViaductTenantNameContext.getCurrent())
                }
            }
        }

    @Test
    fun `FieldResolverExecutor Selector equality works with projected selection sets`() {
        val schema = MockSchema.mk(
            """
                type Test implements Node { id: ID!, bar: Int }
            """.trimIndent()
        )
        val projectedSelections = createEngineSelectionSet(
            SelectionsParser.parse("Node", "id ... on Test { bar }"),
            schema,
            emptyMap()
        ).selectionSetForType("Test")
        assertTrue(projectedSelections is ProjectedEngineSelectionSet)

        val sourceSelections = (projectedSelections as ProjectedEngineSelectionSet).sourceImpl
        val objectValue = mockk<EngineObjectData.Sync>()
        val queryValue = mockk<EngineObjectData.Sync>()
        val sharedObjectGetter: suspend () -> EngineObjectData.Sync = { objectValue }
        val sharedQueryGetter: suspend () -> EngineObjectData.Sync = { queryValue }
        val selector = FieldResolverExecutor.Selector(
            arguments = mapOf("arg1" to "param1"),
            selections = sourceSelections,
            syncObjectValueGetter = sharedObjectGetter,
            syncQueryValueGetter = sharedQueryGetter,
        )
        val other = FieldResolverExecutor.Selector(
            arguments = mapOf("arg1" to "param1"),
            selections = projectedSelections,
            syncObjectValueGetter = sharedObjectGetter,
            syncQueryValueGetter = sharedQueryGetter,
        )

        assertEquals(selector, other)
        assertEquals(selector.hashCode(), other.hashCode())
    }
}

private class TestFieldUnbatchedResolverExecutor(
    override val objectSelectionSet: RequiredSelectionSet? = null,
    override val querySelectionSet: RequiredSelectionSet? = null,
    override val resolverId: String,
    override val unbatchedResolveFn: FieldUnbatchedResolverFn = { _, _, _, _, _ -> null },
) : MockFieldUnbatchedResolverExecutor(
        objectSelectionSet = objectSelectionSet,
        querySelectionSet = querySelectionSet,
        resolverId = resolverId,
        unbatchedResolveFn = unbatchedResolveFn
    ) {
    var lastReceivedLocalContext: EngineExecutionContextImpl? = null
        private set

    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
        lastReceivedLocalContext = context as EngineExecutionContextImpl
        return super.batchResolve(selectors, context)
    }
}

private fun ObjectEngineResultImpl.putResolvedInt(
    fieldName: String,
    value: Int,
    arguments: Map<String, Any?> = emptyMap(),
) = putResolvedValue(fieldName, value, arguments)

private fun ObjectEngineResultImpl.putResolvedValue(
    fieldName: String,
    value: Any?,
    arguments: Map<String, Any?> = emptyMap(),
) {
    computeIfAbsent(ObjectEngineResult.Key(fieldName, fieldName, arguments)) { setter ->
        setter.set(
            ObjectEngineResultImpl.RAW_VALUE_SLOT,
            Value.fromValue(
                FieldResolutionResult(
                    engineResult = value,
                    errors = emptyList(),
                    localContext = CompositeLocalContext.empty,
                    extensions = emptyMap(),
                    originalSource = null,
                )
            )
        )
        setter.set(ObjectEngineResultImpl.ACCESS_CHECK_SLOT, Value.fromValue(null))
    }
}

private fun ObjectEngineResultImpl.putResolvedObject(
    fieldName: String,
    value: ObjectEngineResultImpl,
    arguments: Map<String, Any?> = emptyMap(),
) = putResolvedValue(fieldName, value, arguments)
