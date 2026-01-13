package viaduct.engine.api.instrumentation

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext.noOp
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.schema.DataFetcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerExecutor

class ViaductInstrumentationAdapterTest {
    class TestModernInstrumentation :
        ViaductInstrumentationBase(),
        IViaductInstrumentation.WithBeginFetchObject,
        IViaductInstrumentation.WithBeginCompleteObject,
        IViaductInstrumentation.WithInstrumentDataFetcher,
        IViaductInstrumentation.WithBeginFieldFetch,
        IViaductInstrumentation.WithBeginFieldExecution,
        IViaductInstrumentation.WithBeginFieldCompletion,
        IViaductInstrumentation.WithBeginFieldListCompletion,
        IViaductInstrumentation.WithInstrumentAccessCheck {
        var beginFetchObjectCalled = false
        var beginCompleteObjectCalled = false
        var instrumentDataFetcherCalled = false
        var beginFieldFetchCalled = false
        var beginFieldExecutionCalled = false
        var beginFieldCompletionCalled = false
        var beginFieldListCompletionCalled = false
        var instrumentAccessCheckCalled = false

        override fun beginFetchObject(
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Unit> {
            beginFetchObjectCalled = true
            return noOp()
        }

        override fun beginCompleteObject(
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any> {
            beginCompleteObjectCalled = true
            return noOp()
        }

        override fun instrumentDataFetcher(
            dataFetcher: DataFetcher<*>,
            parameters: InstrumentationFieldFetchParameters,
            state: InstrumentationState?
        ): DataFetcher<*> {
            instrumentDataFetcherCalled = true
            return default.instrumentDataFetcher(dataFetcher, parameters, state)
        }

        override fun beginFieldFetch(
            parameters: InstrumentationFieldFetchParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>? {
            beginFieldFetchCalled = true
            return noOp()
        }

        override fun beginFieldExecution(
            parameters: InstrumentationFieldParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>? {
            beginFieldExecutionCalled = true
            return noOp()
        }

        override fun beginFieldCompletion(
            parameters: InstrumentationFieldCompleteParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>? {
            beginFieldCompletionCalled = true
            return noOp()
        }

        override fun beginFieldListCompletion(
            parameters: InstrumentationFieldCompleteParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>? {
            beginFieldListCompletionCalled = true
            return noOp()
        }

        override fun instrumentAccessCheck(
            checkerExecutor: CheckerExecutor,
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): CheckerExecutor {
            instrumentAccessCheckCalled = true
            return checkerExecutor
        }
    }

    private lateinit var standardInstrumentationBase: ViaductInstrumentationBase

    @BeforeEach
    fun setUp() {
        standardInstrumentationBase = mockk(relaxed = true)
        every { standardInstrumentationBase.asStandardInstrumentation } returns ViaductInstrumentationAdapter(standardInstrumentationBase)
    }

    @Test
    fun `test standard instrumentation adapter`() {
        val instrumentation = standardInstrumentationBase.asStandardInstrumentation

        assertNull(instrumentation.createState(mockk()))

        instrumentation.beginExecution(mockk(), mockk())
        verify { standardInstrumentationBase.beginExecution(any(), any()) }

        instrumentation.beginParse(mockk(), mockk())
        verify { standardInstrumentationBase.beginParse(any(), any()) }

        instrumentation.beginValidation(mockk(), mockk())
        verify { standardInstrumentationBase.beginValidation(any(), any()) }

        instrumentation.beginExecuteOperation(mockk(), mockk())
        verify { standardInstrumentationBase.beginExecuteOperation(any(), any()) }

        instrumentation.beginExecutionStrategy(mockk(), mockk())
        verify { standardInstrumentationBase.beginExecutionStrategy(any(), any()) }

        instrumentation.beginSubscribedFieldEvent(mockk(), mockk())
        verify { standardInstrumentationBase.beginSubscribedFieldEvent(any(), any()) }

        instrumentation.instrumentExecutionInput(mockk(), mockk(), mockk())
        verify { standardInstrumentationBase.instrumentExecutionInput(any(), any(), any()) }

        instrumentation.instrumentDocumentAndVariables(mockk(), mockk(), mockk())
        verify { standardInstrumentationBase.instrumentDocumentAndVariables(any(), any(), any()) }

        instrumentation.instrumentSchema(mockk(), mockk(), mockk())
        verify { standardInstrumentationBase.instrumentSchema(any(), any(), any()) }

        instrumentation.instrumentExecutionContext(mockk(), mockk(), mockk())
        verify { standardInstrumentationBase.instrumentExecutionContext(any(), any(), any()) }

        instrumentation.instrumentExecutionResult(mockk(), mockk(), mockk())
        verify { standardInstrumentationBase.instrumentExecutionResult(any(), any(), any()) }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `delagation is called`() {
        val instrumentationBase = TestModernInstrumentation()
        val instrumentation = instrumentationBase.asStandardInstrumentation

        @Suppress("USELESS_IS_CHECK")
        assertTrue(instrumentation is ViaductModernGJInstrumentation)

        val parameters = mockk<InstrumentationExecutionStrategyParameters>()
        instrumentation.beginFetchObject(parameters, null)
        assert(instrumentationBase.beginFetchObjectCalled)

        instrumentation.beginCompleteObject(parameters, null)
        assert(instrumentationBase.beginCompleteObjectCalled)

        instrumentation.instrumentDataFetcher(mockk(), mockk(), null)
        assert(instrumentationBase.instrumentDataFetcherCalled)

        instrumentation.beginFieldFetch(mockk(), mockk())
        assert(instrumentationBase.beginFieldFetchCalled)

        instrumentation.beginFieldExecution(mockk(), mockk())
        assert(instrumentationBase.beginFieldExecutionCalled)

        instrumentation.beginFieldCompletion(mockk(), mockk())
        assert(instrumentationBase.beginFieldCompletionCalled)

        instrumentation.beginFieldListCompletion(mockk(), mockk())
        assert(instrumentationBase.beginFieldListCompletionCalled)

        instrumentation.instrumentAccessCheck(mockk(), mockk(), mockk())
        assert(instrumentationBase.instrumentAccessCheckCalled)
    }
}
