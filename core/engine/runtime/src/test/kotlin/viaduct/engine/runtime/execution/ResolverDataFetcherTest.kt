@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStepInfo
import graphql.execution.ResultPath
import graphql.execution.values.InputInterceptor
import graphql.execution.values.legacycoercing.LegacyCoercingInputInterceptor
import graphql.schema.GraphQLObjectType
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineObjectDataBuilder
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.instrumentation.ViaductTenantNameContext
import viaduct.engine.api.mocks.FieldUnbatchedResolverFn
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createEngineSelectionSet
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.runtime.EngineExecutionContextExtensions.setExecutionHandle
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.ProxyEngineObjectData
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.context.getLocalContextForType
import viaduct.engine.runtime.createSchema
import viaduct.engine.runtime.dfe.ViaductDataFetchingEnvironment
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime.select.ProjectedEngineSelectionSet
import viaduct.graphql.utils.ParsedSelections
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

@OptIn(ExperimentalCoroutinesApi::class)
class ResolverDataFetcherTest {
    private val allDisabledFlags = MockFlagManager()
    private val executeAccessChecksEnabled = MockFlagManager.create(FlagManager.Flags.EXECUTE_ACCESS_CHECKS)
    private val allFlagSets = listOf(
        allDisabledFlags,
        executeAccessChecksEnabled,
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
            .fieldContainer(testTypeObject)
            .path(ResultPath.parse("/$testField"))
            .build()
        var resolverRan = false
        var lastReceivedObjectValue: Any? = null
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
                unbatchedResolveFn = resolverFn ?: { _, receivedObjectValue, _, _, _ ->
                    resolverRan = true
                    lastReceivedObjectValue = receivedObjectValue
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

        val dataFetchingEnvironment: ViaductDataFetchingEnvironment = mockk()
        val engineResultLocalContext = EngineResultLocalContext(
            rootEngineResult = ObjectEngineResultImpl.newForType(schema.schema.queryType),
            parentEngineResult = ObjectEngineResultImpl.newForType(testTypeObject),
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
            executeAccessChecksInModstrat = baseEngineExecutionContextImpl.executeAccessChecksInModstrat,
            dispatcherRegistry = baseEngineExecutionContextImpl.dispatcherRegistry,
        )

        private fun allRequiredSelectionSets(rss: RequiredSelectionSet): List<RequiredSelectionSet> =
            listOf(rss) + rss.variablesResolvers.flatMap { variablesResolver ->
                variablesResolver.requiredSelectionSet?.let(::allRequiredSelectionSets).orEmpty()
            }

        private val indexedRssPlans = listOfNotNull(requiredSelectionSet, querySelectionSet)
            .flatMap(::allRequiredSelectionSets)
            .associate { rss ->
                rss.id to runBlocking {
                    QueryPlanFactory.Default.buildFromParsedSelections(
                        parameters = queryPlanParameters,
                        parsedSelections = rss.selections,
                        attribution = rss.attribution,
                        executionCondition = rss.executionCondition,
                    )
                }
            }
        private val queryPlanIndex: QueryPlanIndex =
            indexedRssPlans.entries.fold(QueryPlanIndex.empty()) { index, (id, plan) ->
                index.merge(QueryPlanIndex.single(id, plan))
            }

        private val executionConstants = ExecutionParameters.Constants(
            executionContext = mockk<ExecutionContext>(relaxed = true),
            rootEngineResult = engineResultLocalContext.rootEngineResult,
            supervisorScopeFactory = { CoroutineScope(it) },
            rootCoroutineContext = EmptyCoroutineContext,
        )
        private val executionHandle = mockk<ExecutionParameters>(relaxed = true)
        val engineExecutionContextImpl = baseEngineExecutionContextImpl.also {
            every { executionHandle.constants } returns executionConstants
            every { executionHandle.queryPlanIndex } returns queryPlanIndex
            it.setExecutionHandle(executionHandle)
        }

        init {
            every { dataFetchingEnvironment.engineExecutionContext } returns engineExecutionContextImpl
            every { dataFetchingEnvironment.graphQLSchema } returns schema.schema
            every { dataFetchingEnvironment.arguments } returns mapOf("arg1" to "param1")
            every { dataFetchingEnvironment.fieldDefinition } returns testTypeObject.getField(testField)
            every { dataFetchingEnvironment.executionStepInfo } returns executionStepInfo
            every { dataFetchingEnvironment.getLocalContextForType<EngineResultLocalContext>() } returns (engineResultLocalContext)

            // define local var to get around naming collision issue
            every { dataFetchingEnvironment.getLocalContextForType<EngineExecutionContextImpl>() } returns (engineExecutionContextImpl)
            every { dataFetchingEnvironment.getSource<Any>() } returns mockk()
            every { dataFetchingEnvironment.graphQlContext } returns GraphQLContext.newContext()
                .of(InputInterceptor::class.java, LegacyCoercingInputInterceptor.migratesValues())
                .build()
            every { dataFetchingEnvironment.locale } returns Locale.US
        }
    }

    @Test
    fun `queryValueFragment resolves fromObjectField variables against parent object`() {
        runBlocking(Dispatchers.Default) {
            withThreadLocalCoroutineContext {
                val objectSelections = SelectionsParser.parse("TestType", "y")
                val querySelections = SelectionsParser.parse("Query", "placeholder(arg:\$a)")
                val variableResolvers = VariablesResolver.fromSelectionSetVariables(
                    objectSelections = objectSelections,
                    querySelections = querySelections,
                    variables = listOf(FromObjectFieldVariable("a", "y")),
                    forChecker = false,
                )
                val objectSelectionSet = RequiredSelectionSet(
                    selections = objectSelections,
                    variablesResolvers = variableResolvers,
                    forChecker = false,
                )
                val querySelectionSet = RequiredSelectionSet(
                    selections = querySelections,
                    variablesResolvers = variableResolvers,
                    forChecker = false,
                )

                Fixture(
                    expectedResult = null,
                    requiredSelectionSet = objectSelectionSet,
                    querySelectionSet = querySelectionSet,
                    flagManager = allDisabledFlags,
                    testFieldType = "Int",
                    resolverFn = { _, _, queryValue, _, _ ->
                        queryValue.fetchAs<Int>("placeholder") * 3
                    },
                ).apply {
                    engineResultLocalContext.parentEngineResult.putResolvedInt("y", 2)
                    engineResultLocalContext.queryEngineResult.putResolvedInt("placeholder", 10, mapOf("arg" to 2))

                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).get(2, TimeUnit.SECONDS)
                    assertEquals(30, receivedResult)
                }
            }
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
                    flagManager = executeAccessChecksEnabled
                ).apply {
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)

                    // verify that localContext has dataFetchingEnvironment copied
                    assertEquals(dataFetchingEnvironment, executor.lastReceivedLocalContext?.dataFetchingEnvironment)
                }
            }
        }

    @Test
    fun `test sync value getter passes ProxyEngineObjectData as objectValue to resolver`(): Unit =
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
                    engineResultLocalContext.parentEngineResult.computeIfAbsent(
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
                    assertTrue(lastReceivedObjectValue is ProxyEngineObjectData)
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
        val objectValue = mockk<EngineObjectData>()
        val queryValue = mockk<EngineObjectData>()
        val selector = FieldResolverExecutor.Selector(
            arguments = mapOf("arg1" to "param1"),
            objectValue = objectValue,
            queryValue = queryValue,
            selections = sourceSelections
        )
        val other = FieldResolverExecutor.Selector(
            arguments = mapOf("arg1" to "param1"),
            objectValue = objectValue,
            queryValue = queryValue,
            selections = projectedSelections
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
