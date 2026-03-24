@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.instrumentation

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionId
import graphql.execution.instrumentation.DocumentAndVariables
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationCreateStateParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.execution.instrumentation.parameters.InstrumentationValidationParameters
import graphql.language.Document
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLSchema
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlin.test.assertNotNull
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.runtime.execution.withThreadLocalCoroutineContext

class ConditionallyEnabledInstrumentationTest {
    // ─────────────── mocks reused across tests ───────────────
    private val executionResult = mockk<ExecutionResult>()
    private val dataFetcher = mockk<DataFetcher<*>>()
    private val documentVariables = mockk<DocumentAndVariables>()
    private val executionContext = mockk<ExecutionContext>()
    private val graphQlSchema = mockk<GraphQLSchema>()

    // ─────────────────────────────────────────────────────────
    //  1.  createStateAsync
    // ─────────────────────────────────────────────────────────
    @TestFactory
    fun createStateAsync() =
        createTestCases<InstrumentationCreateStateParameters>(
            createParameters = { ei ->
                InstrumentationCreateStateParameters(mockk(), ei)
            },
            runAdapter = { p, _ ->
                // we’re *testing* createStateAsync here, so call it directly
                runBlocking {
                    withThreadLocalCoroutineContext {
                        createStateAsync(p)?.await()
                    }
                }
            },
            verifyMock = { p, _ -> createState(p) },
            times = 1, // call once – no cache to check
            callCreateStateFirst = false // don’t pre-create the state; this *is* the call
        )

    // ─────────────────────────────────────────────────────────
    //  2.  beginExecution
    // ─────────────────────────────────────────────────────────
    @TestFactory
    fun beginExecution() =
        createTestCases<InstrumentationExecutionParameters>(
            createParameters = { ei ->
                InstrumentationExecutionParameters(ei, mockk())
            },
            runAdapter = { p, s -> beginExecution(p, s) },
            verifyMock = { p, _ -> beginExecution(p, null) }
        )

    @TestFactory
    fun beginParse() =
        createTestCases<InstrumentationExecutionParameters>(
            createParameters = { ei ->
                InstrumentationExecutionParameters(ei, mockk())
            },
            runAdapter = { p, s -> beginParse(p, s) },
            verifyMock = { p, _ -> beginParse(p, null) }
        )

    @TestFactory
    fun beginValidation() =
        createTestCases<InstrumentationValidationParameters>(
            createParameters = { ei ->
                InstrumentationValidationParameters(ei, mockk<Document>(), mockk())
            },
            runAdapter = { p, s -> beginValidation(p, s) },
            verifyMock = { p, _ -> beginValidation(p, null) }
        )

    @TestFactory
    fun beginExecuteOperation() =
        createTestCases<InstrumentationExecuteOperationParameters>(
            createParameters = { ei ->
                InstrumentationExecuteOperationParameters(
                    mockk {
                        every { executionInput } returns ei
                    }
                )
            },
            runAdapter = { p, s -> beginExecuteOperation(p, s) },
            verifyMock = { p, _ -> beginExecuteOperation(p, null) }
        )

    @TestFactory
    fun beginExecutionStrategy() =
        createTestCases<InstrumentationExecutionStrategyParameters>(
            createParameters = { ei ->
                InstrumentationExecutionStrategyParameters(
                    mockk<ExecutionContext> { every { executionInput } returns ei },
                    mockk()
                )
            },
            runAdapter = { p, s -> beginExecutionStrategy(p, s) },
            verifyMock = { p, _ -> beginExecutionStrategy(p, null) }
        )

    @TestFactory
    fun beginSubscribedFieldEvent() =
        createTestCases<InstrumentationFieldParameters>(
            createParameters = { ei ->
                InstrumentationFieldParameters(
                    mockk<ExecutionContext> { every { executionInput } returns ei },
                    mockk()
                )
            },
            runAdapter = { p, s -> beginSubscribedFieldEvent(p, s) },
            verifyMock = { p, _ -> beginSubscribedFieldEvent(p, null) }
        )

    @TestFactory
    fun beginFieldExecution() =
        createTestCases<InstrumentationFieldParameters>(
            createParameters = { ei ->
                InstrumentationFieldParameters(
                    mockk<ExecutionContext> { every { executionInput } returns ei },
                    mockk()
                )
            },
            runAdapter = { p, s -> beginFieldExecution(p, s) },
            verifyMock = { p, _ -> beginFieldExecution(p, null) }
        )

    @TestFactory
    @Suppress("DEPRECATION")
    fun beginFieldFetch() =
        createTestCases<InstrumentationFieldFetchParameters>(
            createParameters = { ei ->
                InstrumentationFieldFetchParameters(
                    mockk<ExecutionContext> { every { executionInput } returns ei },
                    { mockk<DataFetchingEnvironment>() },
                    mockk(),
                    false
                )
            },
            runAdapter = { p, s -> beginFieldFetch(p, s) },
            verifyMock = { p, _ -> beginFieldFetch(p, null) }
        )

    @TestFactory
    fun beginFieldCompletion() =
        createTestCases<InstrumentationFieldCompleteParameters>(
            createParameters = { ei ->
                InstrumentationFieldCompleteParameters(
                    mockk<ExecutionContext> { every { executionInput } returns ei },
                    mockk(),
                    mockk(),
                    ""
                )
            },
            runAdapter = { p, s -> beginFieldCompletion(p, s) },
            verifyMock = { p, _ -> beginFieldCompletion(p, null) }
        )

    @TestFactory
    fun beginFieldListComplete() =
        createTestCases<InstrumentationFieldCompleteParameters>(
            createParameters = { ei ->
                InstrumentationFieldCompleteParameters(
                    mockk<ExecutionContext> { every { executionInput } returns ei },
                    mockk(),
                    mockk(),
                    listOf("")
                )
            },
            runAdapter = { p, s -> beginFieldListCompletion(p, s) },
            verifyMock = { p, _ -> beginFieldListCompletion(p, null) }
        )

    // ─────────────────────────────────────────────────────────
    //  3.  instrument* helpers
    // ─────────────────────────────────────────────────────────
    @TestFactory
    fun instrumentExecutionInput() =
        createTestCases<InstrumentationExecutionParameters>(
            createParameters = { ei ->
                InstrumentationExecutionParameters(ei, mockk())
            },
            runAdapter = { p, s ->
                instrumentExecutionInput(p.executionInput, p, s)
            },
            verifyMock = { p, _ ->
                instrumentExecutionInput(p.executionInput, p, null)
            }
        )

    @TestFactory
    fun instrumentDocumentAndVariables() =
        createTestCases<InstrumentationExecutionParameters>(
            createParameters = { ei ->
                InstrumentationExecutionParameters(ei, mockk())
            },
            runAdapter = { p, s ->
                instrumentDocumentAndVariables(documentVariables, p, s)
            },
            verifyMock = { p, _ ->
                instrumentDocumentAndVariables(documentVariables, p, null)
            }
        )

    @TestFactory
    fun instrumentSchema() =
        createTestCases<InstrumentationExecutionParameters>(
            createParameters = { ei ->
                InstrumentationExecutionParameters(ei, mockk())
            },
            runAdapter = { p, s -> instrumentSchema(graphQlSchema, p, s) },
            verifyMock = { p, _ -> instrumentSchema(graphQlSchema, p, null) }
        )

    @TestFactory
    fun instrumentExecutionContext() =
        createTestCases<InstrumentationExecutionParameters>(
            createParameters = { ei ->
                InstrumentationExecutionParameters(ei, mockk())
            },
            runAdapter = { p, s -> instrumentExecutionContext(executionContext, p, s) },
            verifyMock = { p, _ -> instrumentExecutionContext(executionContext, p, null) }
        )

    @TestFactory
    fun instrumentDataFetcher() =
        createTestCases<InstrumentationFieldFetchParameters>(
            createParameters = { ei ->
                InstrumentationFieldFetchParameters(
                    mockk<ExecutionContext> { every { executionInput } returns ei },
                    { mockk<DataFetchingEnvironment>() },
                    mockk(),
                    false
                )
            },
            runAdapter = { p, s -> instrumentDataFetcher(dataFetcher, p, s) },
            verifyMock = { p, _ -> instrumentDataFetcher(dataFetcher, p, null) }
        )

    @TestFactory
    fun instrumentExecutionResult() =
        createTestCases<InstrumentationExecutionParameters>(
            createParameters = { ei ->
                InstrumentationExecutionParameters(ei, mockk())
            },
            runAdapter = { p, s -> instrumentExecutionResult(executionResult, p, s) },
            verifyMock = { p, _ -> instrumentExecutionResult(executionResult, p, null) },
            times = 1 // normally called once per execution
        )

    // ─────────────────────────────────────────────────────────
    //  Shared test-builder
    // ─────────────────────────────────────────────────────────
    private inline fun <reified T : Any> createTestCases(
        crossinline createParameters: (ExecutionInput) -> T,
        crossinline runAdapter: ConditionallyEnabledInstrumentationAdapter.(T, InstrumentationState?) -> Unit,
        crossinline verifyMock: suspend TestConditionalInstrumentation.(T, InstrumentationState?) -> Unit,
        crossinline verifyAdapter: ConditionallyEnabledInstrumentationAdapter.(T, InstrumentationState?) -> Unit = { _, _ -> },
        times: Int = 2,
        callCreateStateFirst: Boolean = true
    ): List<DynamicTest> {
        val runCase: suspend (Boolean) -> Unit = { enabled ->
            val conditional = spyk(TestConditionalInstrumentation(enabled))
            val adapter = ConditionallyEnabledInstrumentationAdapter(conditional)
            val input = newExecutionInput()
            val params = createParameters(input)

            // Obtain the wrapped state once, exactly as GraphQL-Java would
            val wrappedState: InstrumentationState? =
                if (callCreateStateFirst) {
                    adapter.createStateAsync(
                        InstrumentationCreateStateParameters(mockk(), input)
                    )?.await()
                        .also { assertNotNull(it) }
                } else {
                    null
                }

            repeat(times) { adapter.runAdapter(params, wrappedState) }

            if (enabled) {
                coVerify(exactly = times) { conditional.verifyMock(params, null) }
            } else {
                coVerify(exactly = 0) { conditional.verifyMock(params, null) }
            }

            adapter.verifyAdapter(params, wrappedState)

            // decision happens exactly once per execution
            verify(exactly = 1) { conditional.isInstrumentationEnabled(input) }
        }

        return listOf(
            dynamicTest("instrumentation enabled") { runBlocking { runCase(true) } },
            dynamicTest("instrumentation disabled") { runBlocking { runCase(false) } }
        )
    }

    // ─────────────────────────────────────────────────────────
    //  Test helper-instrumentation
    // ─────────────────────────────────────────────────────────
    private class TestConditionalInstrumentation(
        private val enabled: Boolean
    ) : ConditionallyEnabledInstrumentation(),
        IViaductInstrumentation.WithBeginFieldExecution,
        IViaductInstrumentation.WithBeginFieldFetch,
        IViaductInstrumentation.WithBeginFieldCompletion,
        IViaductInstrumentation.WithBeginFieldListCompletion,
        IViaductInstrumentation.WithInstrumentDataFetcher {
        override fun isInstrumentationEnabled(executionInput: ExecutionInput) = enabled

        override fun beginFieldExecution(
            parameters: InstrumentationFieldParameters,
            state: InstrumentationState?
        ) = default.beginFieldExecution(parameters, state)

        @Suppress("DEPRECATION")
        override fun beginFieldFetch(
            parameters: InstrumentationFieldFetchParameters,
            state: InstrumentationState?
        ) = default.beginFieldFetch(parameters, state)

        override fun beginFieldCompletion(
            parameters: InstrumentationFieldCompleteParameters,
            state: InstrumentationState?
        ) = default.beginFieldCompletion(parameters, state)

        override fun beginFieldListCompletion(
            parameters: InstrumentationFieldCompleteParameters,
            state: InstrumentationState?
        ) = default.beginFieldListCompletion(parameters, state)

        override fun instrumentDataFetcher(
            dataFetcher: DataFetcher<*>,
            parameters: InstrumentationFieldFetchParameters,
            state: InstrumentationState?
        ) = default.instrumentDataFetcher(dataFetcher, parameters, state)
    }

    private fun newExecutionInput() =
        ExecutionInput.newExecutionInput("")
            .executionId(ExecutionId.generate())
            .build()
}
