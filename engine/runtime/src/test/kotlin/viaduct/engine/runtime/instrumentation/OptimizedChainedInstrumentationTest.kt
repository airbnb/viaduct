package viaduct.engine.runtime.instrumentation

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext.noOp
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.ViaductInstrumentationAdapter
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase

class OptimizedChainedInstrumentationTest {
    private lateinit var noInteractionInstrumentationBase: ViaductInstrumentationBase
    private lateinit var noInteractionInstrumentation: ViaductInstrumentationAdapter

    @BeforeEach
    fun setUp() {
        noInteractionInstrumentation = mockk()
        noInteractionInstrumentationBase = mockk()
        every { noInteractionInstrumentationBase.asStandardInstrumentation() } returns noInteractionInstrumentation
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    class TestBeginFetchObjectInstrumentation :
        ViaductInstrumentationBase(),
        IViaductInstrumentation.WithBeginFetchObject {
        var called = false

        override fun beginFetchObject(
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Unit> {
            called = true
            return noOp()
        }
    }

    @Test
    fun `test beginFetchedObject optimization`() {
        val beginFetchObjectInstrumentation = TestBeginFetchObjectInstrumentation()
        val optimized = OptimizedChainedInstrumentation(
            listOf(beginFetchObjectInstrumentation, noInteractionInstrumentationBase)
        )
        val params = mockk<InstrumentationExecutionStrategyParameters>()
        val state = mockk<InstrumentationState>()
        val context = optimized.beginFetchObject(params, state)
        assertNotNull(context)
        assert(beginFetchObjectInstrumentation.called)
        verify { noInteractionInstrumentation wasNot Called }
    }

    class TestBeginCompleteObjectInstrumentation :
        ViaductInstrumentationBase(),
        IViaductInstrumentation.WithBeginCompleteObject {
        var called = false

        override fun beginCompleteObject(
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any> {
            called = true
            return noOp()
        }
    }

    @Test
    fun `test beginCompleteObject optimization`() {
        val beginCompleteObjectInstrumentation = TestBeginCompleteObjectInstrumentation()
        val optimized = OptimizedChainedInstrumentation(
            listOf(beginCompleteObjectInstrumentation, noInteractionInstrumentationBase)
        )
        val params = mockk<InstrumentationExecutionStrategyParameters>()
        val state = mockk<InstrumentationState>()
        val context = optimized.beginCompleteObject(params, state)
        assertNotNull(context)
        assert(beginCompleteObjectInstrumentation.called)
        verify { noInteractionInstrumentation wasNot Called }
    }

    class TestInstrumentAccessCheckInstrumentation :
        ViaductInstrumentationBase(),
        IViaductInstrumentation.WithInstrumentAccessCheck {
        var called = false

        override fun instrumentAccessCheck(
            checkerExecutor: CheckerExecutor,
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): CheckerExecutor {
            called = true
            return checkerExecutor
        }
    }

    @Test
    fun `test instrumentAccessCheck optimization`() {
        val instrumentAccessCheckInstrumentation = TestInstrumentAccessCheckInstrumentation()
        val optimized = OptimizedChainedInstrumentation(
            listOf(instrumentAccessCheckInstrumentation, noInteractionInstrumentationBase)
        )
        val params = mockk<InstrumentationExecutionStrategyParameters>()
        val state = mockk<InstrumentationState>()
        val checkerExecutor = mockk<CheckerExecutor>()
        val result = optimized.instrumentAccessCheck(checkerExecutor, params, state)
        assertNotNull(result)
        assert(instrumentAccessCheckInstrumentation.called)
        verify { noInteractionInstrumentation wasNot Called }
    }
}
