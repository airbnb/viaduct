package viaduct.engine.api.instrumentation

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext.noOp
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.ShadowFieldExecutionComparison

class ViaductInstrumentationAdapterTest {
    class TestModernInstrumentation :
        ViaductInstrumentationBase(),
        IViaductInstrumentation.WithBeginFetchObject,
        IViaductInstrumentation.WithBeginCompleteObject,
        IViaductInstrumentation.WithInstrumentDataFetcher,
        IViaductInstrumentation.WithBeginFieldFetch,
        IViaductInstrumentation.WithBeginFieldExecution,
        IViaductInstrumentation.WithShadowFieldExecution,
        IViaductInstrumentation.WithBeginFieldCompletion,
        IViaductInstrumentation.WithBeginFieldListCompletion,
        IViaductInstrumentation.WithInstrumentAccessCheck,
        IViaductInstrumentation.WithBeginNodeFetching {
        var beginFetchObjectCalled = false
        var beginCompleteObjectCalled = false
        var instrumentDataFetcherCalled = false
        var beginFieldFetchCalled = false
        var beginFieldExecutionCalled = false
        var shadowFieldExecutionCalled = false
        var beginFieldCompletionCalled = false
        var beginFieldListCompletionCalled = false
        var instrumentAccessCheckCalled = false
        var beginNodeFetchingCalled = false

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

        override fun requestShadowFieldExecution(
            parameters: InstrumentationFieldParameters,
            state: InstrumentationState?,
        ): ShadowFieldExecutionComparison? {
            shadowFieldExecutionCalled = true
            return null
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
            dataFetchingEnvironment: DataFetchingEnvironment,
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): CheckerExecutor {
            instrumentAccessCheckCalled = true
            return checkerExecutor
        }

        override fun beginNodeFetching(
            parameters: InstrumentNodeFetchingParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>? {
            beginNodeFetchingCalled = true
            return noOp()
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
    fun `beginExecutionStrategy uses the base instrumentation default`() {
        val instrumentation = ViaductInstrumentationBase().asStandardInstrumentation

        assertNotNull(instrumentation.beginExecutionStrategy(mockk(), null))
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

        instrumentation.requestShadowFieldExecution(mockk(), mockk())
        assert(instrumentationBase.shadowFieldExecutionCalled)

        instrumentation.beginFieldCompletion(mockk(), mockk())
        assert(instrumentationBase.beginFieldCompletionCalled)

        instrumentation.beginFieldListCompletion(mockk(), mockk())
        assert(instrumentationBase.beginFieldListCompletionCalled)

        instrumentation.instrumentAccessCheck(mockk(), mockk<DataFetchingEnvironment>(), mockk(), mockk())
        assert(instrumentationBase.instrumentAccessCheckCalled)

        val nodeFetchingParams = InstrumentNodeFetchingParameters(
            requiredBy = null,
            resolverMetadata = null,
        )
        instrumentation.beginNodeFetching(nodeFetchingParams, null)
        assert(instrumentationBase.beginNodeFetchingCalled)
    }

    @Test
    fun `transformResult transforms synchronous data fetcher results`() {
        val instrumentation = TestModernInstrumentation()
        val transformedDataFetcher =
            instrumentation.transformResult(DataFetcher<Any?> { "value" }) {
                "$it transformed"
            }

        assertEquals("value transformed", transformedDataFetcher.get(mockk()))
    }

    @Suppress("USELESS_IS_CHECK") // intentional: verifies the adapter returns the expected type
    @Test
    fun `asStandardInstrumentation method returns adapter`() {
        val base = TestModernInstrumentation()
        val adapter = base.asStandardInstrumentation()
        assertTrue(adapter is ViaductModernGJInstrumentation)
    }

    @Test
    fun `beginNodeFetching returns noOp when WithBeginNodeFetching is not implemented`() {
        val instrumentation = standardInstrumentationBase.asStandardInstrumentation
        val nodeFetchingParams = InstrumentNodeFetchingParameters(
            requiredBy = null,
            resolverMetadata = null,
        )
        val result = instrumentation.beginNodeFetching(nodeFetchingParams, null)
        assertNotNull(result)
    }
}
