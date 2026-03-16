@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.execution.ExecutionStepInfo
import graphql.execution.ResultPath
import graphql.execution.values.InputInterceptor
import graphql.execution.values.legacycoercing.LegacyCoercingInputInterceptor
import graphql.schema.GraphQLObjectType
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import java.util.concurrent.CompletionException
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectDataBuilder
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductDataFetchingEnvironment
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.instrumentation.ViaductTenantNameContext
import viaduct.engine.api.mocks.FieldUnbatchedResolverFn
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.ProxyEngineObjectData
import viaduct.engine.runtime.SyncFieldResolverDispatcher
import viaduct.engine.runtime.SyncProxyEngineObjectData
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.context.getLocalContextForType
import viaduct.engine.runtime.createSchema
import viaduct.engine.runtime.mocks.ContextMocks
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
        val expectedResult: String?,
        val requiredSelectionSet: RequiredSelectionSet?,
        val flagManager: FlagManager,
        val resolveWithException: Boolean = false,
        val testType: String = "TestType",
        val testField: String = "testField",
        val tenantNameResolver: TenantNameResolver = TenantNameResolver(),
    ) {
        val schema: ViaductSchema = createSchema(
            """
            type Query { placeholder: Int }
            type $testType {
                $testField(id:Int): String
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
            .type(schema.schema.getTypeAs("String"))
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
                resolverId = resolverId,
                unbatchedResolveFn = { _, _, _, _, _ -> throw RuntimeException("test MockResolverExecutor") },
            )
        } else {
            TestFieldUnbatchedResolverExecutor(
                objectSelectionSet = requiredSelectionSet,
                resolverId = resolverId,
                unbatchedResolveFn = { _, receivedObjectValue, _, _, _ ->
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
            fieldResolverDispatcher = if (flagManager.isEnabled(FlagManager.Flags.ENABLE_SYNC_VALUE_COMPUTATION)) {
                SyncFieldResolverDispatcher(FieldResolverDispatcherImpl(executor))
            } else {
                FieldResolverDispatcherImpl(executor)
            },
            tenantNameResolver = tenantNameResolver,
        )

        val dataFetchingEnvironment: ViaductDataFetchingEnvironment = mockk()
        val engineResultLocalContext = EngineResultLocalContext(
            rootEngineResult = ObjectEngineResultImpl.newForType(schema.schema.queryType),
            parentEngineResult = ObjectEngineResultImpl.newForType(testTypeObject),
            queryEngineResult = ObjectEngineResultImpl.newForType(schema.schema.queryType),
            executionStrategyParams = mockk(),
            executionContext = mockk()
        )
        val engineExecutionContextImpl = ContextMocks(
            myFullSchema = schema,
            myFlagManager = flagManager,
        ).engineExecutionContextImpl

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
    fun `test sync value computation enabled passes SyncProxyEngineObjectData to resolver`(): Unit =
        runBlocking(Dispatchers.Default) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = RequiredSelectionSet(
                        SelectionsParser.parse("TestType", "testField"),
                        emptyList(),
                        forChecker = false
                    ),
                    flagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_SYNC_VALUE_COMPUTATION),
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
                    assertTrue(lastReceivedObjectValue is SyncProxyEngineObjectData)
                }
            }
        }

    @Test
    fun `test sync value computation disabled passes ProxyEngineObjectData to resolver`(): Unit =
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
}

private class TestFieldUnbatchedResolverExecutor(
    override val objectSelectionSet: RequiredSelectionSet? = null,
    override val querySelectionSet: RequiredSelectionSet? = null,
    override val resolverId: String,
    override val unbatchedResolveFn: FieldUnbatchedResolverFn = { _, _, _, _, _ -> null },
) : MockFieldUnbatchedResolverExecutor(objectSelectionSet, querySelectionSet, resolverId = resolverId, unbatchedResolveFn = unbatchedResolveFn) {
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
