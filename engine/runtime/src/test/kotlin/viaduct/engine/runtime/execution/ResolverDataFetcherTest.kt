@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import com.airbnb.viaduct.errors.ViaductPermissionDeniedException
import graphql.execution.ExecutionStepInfo
import graphql.language.OperationDefinition
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletionException
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectDataBuilder
import viaduct.engine.api.FieldResolverExecutor
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.derived.DerivedFieldQueryMetadata
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.fragment.FragmentFieldEngineResolutionResult
import viaduct.engine.api.mocks.FieldUnbatchedResolverFn
import viaduct.engine.api.mocks.MockCheckerExecutor
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.CheckerDispatcherImpl
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.getLocalContextForType
import viaduct.engine.runtime.mkSchema
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

@OptIn(ExperimentalCoroutinesApi::class)
class ResolverDataFetcherTest {
    private val allDisabledFlags = MockFlagManager()
    private val allEnabledFlags = MockFlagManager.Enabled
    private val allFlagSets = listOf(
        allDisabledFlags,
        allEnabledFlags,
    )

    private class Fixture(
        val expectedResult: String?,
        val requiredSelectionSet: RequiredSelectionSet?,
        val flagManager: FlagManager,
        val checkerExecutor: CheckerExecutor? = null,
        val resolveWithException: Boolean = false,
        val testType: String = "TestType",
        val testField: String = "testField"
    ) {
        val schema: ViaductSchema = mkSchema(
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
            .build()
        var resolverRan = false
        val resolverId = "$testType.$testField"
        val objectValue = EngineObjectDataBuilder.from(testTypeObject).put(testField, expectedResult).build()
        val checkerDispatcher = if (checkerExecutor == null) null else CheckerDispatcherImpl(checkerExecutor)
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
                unbatchedResolveFn = { _, _, _, _, _ ->
                    resolverRan = true
                    expectedResult
                },
            )
        }
        val resolverDataFetcher = ResolverDataFetcher(
            typeName = testType,
            fieldName = testField,
            fieldResolverDispatcher = FieldResolverDispatcherImpl(executor),
            checkerDispatcher = checkerDispatcher,
        )

        val dataFetchingEnvironment: DataFetchingEnvironment = mockk()
        val operationDefinition: OperationDefinition = mockk()
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
            every { dataFetchingEnvironment.graphQLSchema } returns schema.schema
            every { dataFetchingEnvironment.arguments } returns mapOf("arg1" to "param1")
            every { dataFetchingEnvironment.fieldDefinition } returns testTypeObject.getField(testField)
            every { dataFetchingEnvironment.executionStepInfo } returns executionStepInfo
            every { dataFetchingEnvironment.getLocalContextForType<EngineResultLocalContext>() } returns (engineResultLocalContext)

            // define local var to get around naming collision issue
            every { dataFetchingEnvironment.getLocalContextForType<EngineExecutionContextImpl>() } returns (engineExecutionContextImpl)
            every { dataFetchingEnvironment.getSource<Any>() } returns mockk()
            every { dataFetchingEnvironment.operationDefinition } returns operationDefinition
            every { operationDefinition.operation } returns OperationDefinition.Operation.QUERY
        }
    }

    @Test
    fun `test resolving with null objectSelectionSet`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
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
    fun `test resolving with existing object selection set -- modern disabled`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = RequiredSelectionSet(
                        SelectionsParser.parse("TestType", "testField"),
                        emptyList()
                    ),
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
    fun `test resolving with existing object selection set -- modern enabled`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = RequiredSelectionSet(
                        SelectionsParser.parse("TestType", "TestField"),
                        emptyList()
                    ),
                    flagManager = allEnabledFlags
                ).apply {
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)

                    // verify that localContext has dataFetchingEnvironment copied
                    assertEquals(dataFetchingEnvironment, executor.lastReceivedLocalContext?.dataFetchingEnvironment)
                }
            }
        }

    @Test
    fun `test resolving with existing object selection set -- modern enabled but not for mod fields`() {
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = RequiredSelectionSet(
                        SelectionsParser.parse("TestType", "TestField"),
                        emptyList()
                    ),
                    flagManager = allDisabledFlags
                ).apply {
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)
                    assertEquals(dataFetchingEnvironment, executor.lastReceivedLocalContext?.dataFetchingEnvironment)
                }
            }
        }
    }

    @Test
    fun `test resolving required selections with FromArgument variables -- all flag configurations`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
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
                                        variables = listOf(
                                            FromArgumentVariable("myid", "id")
                                        )
                                    ),
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
    fun `test access check not run, in modstrat instead`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                var checkRan = false
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = allEnabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        executeFn = { _, _ -> checkRan = true }
                    )
                ).apply {
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)
                    assertFalse(checkRan)
                }
            }
        }

    @Test
    fun `test fail access check`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = null,
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        executeFn = { _, _ -> throw ViaductPermissionDeniedException("test MockFailingCheckerExecutor") }
                    )
                ).apply {
                    val e = assertThrows<CompletionException> {
                        resolverDataFetcher.get(dataFetchingEnvironment).join()
                    }
                    assertTrue(e.cause is ViaductPermissionDeniedException)
                }
            }
        }

    @Test
    fun `test success access check`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        executeFn = { _, _ -> }
                    )
                ).apply {
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)
                }
            }
        }

    @Test
    fun `test success access check but resolve with exception`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = null,
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        executeFn = { _, _ -> }
                    ),
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
    fun `test fail access check with resolver exception`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = null,
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        executeFn = { _, _ -> throw ViaductPermissionDeniedException("test MockFailingCheckerExecutor") }
                    ),
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
    fun `test success access check on mutation`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        executeFn = { _, _ -> }
                    ),
                    testType = "Mutation",
                    testField = "placeholder"
                ).apply {
                    every { operationDefinition.operation } returns OperationDefinition.Operation.MUTATION
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)
                }
            }
        }

    @Test
    fun `test fail access check on mutation`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = null,
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        executeFn = { _, _ -> throw ViaductPermissionDeniedException("test MockFailingCheckerExecutor") }
                    ),
                    testType = "Mutation",
                    testField = "placeholder"
                ).apply {
                    every { operationDefinition.operation } returns OperationDefinition.Operation.MUTATION

                    val e = assertThrows<CompletionException> {
                        resolverDataFetcher.get(dataFetchingEnvironment).join()
                    }
                    assertTrue(e.cause is ViaductPermissionDeniedException)
                    assertFalse(resolverRan)
                }
            }
        }

    @Test
    fun `test fail access check on mutation with resolver exception`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = null,
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        executeFn = { _, _ -> throw ViaductPermissionDeniedException("test MockFailingCheckerExecutor") }
                    ),
                    testType = "Mutation",
                    testField = "placeholder",
                    resolveWithException = true
                ).apply {
                    every { operationDefinition.operation } returns OperationDefinition.Operation.MUTATION

                    val e = assertThrows<CompletionException> {
                        resolverDataFetcher.get(dataFetchingEnvironment).join()
                    }
                    assertTrue(e.cause is ViaductPermissionDeniedException)
                    assertFalse(resolverRan)
                }
            }
        }

    @Test
    fun `test success access check with no selection set in old engine`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        executeFn = { _, _ -> }
                    )
                ).apply {
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)
                }
            }
        }

    @Test
    fun `test success access check with single selection set in old engine`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        requiredSelectionSets = mapOf(
                            "key" to RequiredSelectionSet(
                                SelectionsParser.parse("TestType", "himejiId"),
                                emptyList()
                            )
                        ),
                        executeFn = { _, _ -> }
                    )
                ).apply {
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)
                }
            }
        }

    @Test
    fun `test success access check with multiple selection set in old engine`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        requiredSelectionSets = mapOf(
                            "checker_0" to RequiredSelectionSet(
                                SelectionsParser.parse("TestType", "himejiId"),
                                emptyList()
                            ),
                            "checker_1" to RequiredSelectionSet(
                                SelectionsParser.parse("TestType", "testField"),
                                emptyList()
                            )
                        ),
                        executeFn = { _, _ -> }
                    )
                ).apply {
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)
                }
            }
        }

    @Test
    fun `test success access check with selection set and resolver with selection set in old engine`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = RequiredSelectionSet(
                        SelectionsParser.parse("TestType", "TestField"),
                        emptyList()
                    ),
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        requiredSelectionSets = mapOf(
                            "key" to RequiredSelectionSet(
                                SelectionsParser.parse("TestType", "himejiId"),
                                emptyList()
                            )
                        ),
                        executeFn = { _, _ -> }
                    )
                ).apply {
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)
                }
            }
        }

    @Test
    fun `test success access check with selection set of multiple fragments and resolver with selection set in old engine`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                val checkerDocString =
                    """
                        fragment Main on TestType {
                            himejiId
                            ... TestFragment
                        }
                        fragment TestFragment on TestType {
                            foo {
                                bar
                            }
                        }
                    """.trimIndent()
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = RequiredSelectionSet(
                        SelectionsParser.parse("TestType", "TestField"),
                        emptyList()
                    ),
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        requiredSelectionSets = mapOf(
                            "key" to RequiredSelectionSet(
                                SelectionsParser.parse("TestType", checkerDocString),
                                emptyList()
                            )
                        ),
                        executeFn = { _, _ -> }
                    )
                ).apply {
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)
                }
            }
        }

    @Test
    fun `test success access check when modstrat is enabled for all, but not execute access check in modstrat`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        executeFn = { _, _ -> }
                    )
                ).apply {
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)
                }
            }
        }

    @Test
    fun `test success access check with single selection set, when modstrat is enabled for all, but not execute access check in modstrat`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        requiredSelectionSets = mapOf(
                            "key" to RequiredSelectionSet(
                                SelectionsParser.parse("TestType", "himejiId"),
                                emptyList()
                            )
                        ),
                        executeFn = { _, _ -> }
                    )
                ).apply {
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)
                }
            }
        }

    @Test
    fun `test success access check with multiple selection sets, when modstrat is enabled for all, but not execute access check in modstrat`(): Unit =
        runBlocking(TestCoroutineDispatcher()) {
            withThreadLocalCoroutineContext {
                Fixture(
                    expectedResult = "test fetched result",
                    requiredSelectionSet = null,
                    flagManager = allDisabledFlags,
                    checkerExecutor = MockCheckerExecutor(
                        requiredSelectionSets = mapOf(
                            "checker_0" to RequiredSelectionSet(
                                SelectionsParser.parse("TestType", "himejiId"),
                                emptyList()
                            ),
                            "checker_1" to RequiredSelectionSet(
                                SelectionsParser.parse("TestType", "testField"),
                                emptyList()
                            )
                        ),
                        executeFn = { _, _ -> }
                    )
                ).apply {
                    val receivedResult = resolverDataFetcher.get(dataFetchingEnvironment).join()
                    assertEquals(expectedResult, receivedResult)
                }
            }
        }
}

private class TestFieldUnbatchedResolverExecutor(
    override val objectSelectionSet: RequiredSelectionSet? = null,
    override val querySelectionSet: RequiredSelectionSet? = null,
    override val metadata: Map<String, String> = emptyMap(),
    override val resolverId: String,
    override val unbatchedResolveFn: FieldUnbatchedResolverFn = { _, _, _, _, _ -> null },
) : MockFieldUnbatchedResolverExecutor(objectSelectionSet, querySelectionSet, metadata, resolverId, unbatchedResolveFn) {
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

private class MockFragmentLoader(val result: Any) : FragmentLoader {
    override suspend fun loadFromEngine(
        fragment: Fragment,
        metadata: DerivedFieldQueryMetadata,
        source: Any?,
        dataFetchingEnvironment: DataFetchingEnvironment?
    ): FragmentFieldEngineResolutionResult = TODO()

    override suspend fun loadEngineObjectData(
        fragment: Fragment,
        metadata: DerivedFieldQueryMetadata,
        source: Any,
        dataFetchingEnvironment: DataFetchingEnvironment
    ): ObjectEngineResult = result as ObjectEngineResult
}
