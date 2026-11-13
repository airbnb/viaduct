package viaduct.engine.runtime.instrumentation

import graphql.ExecutionInput
import graphql.GraphQLContext
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionContextBuilder
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.context.findLocalContextForType
import viaduct.engine.runtime.context.isIntrospective
import viaduct.engine.runtime.context.setIsEngine2
import viaduct.engine.runtime.createSchema
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.FlagManager

internal class ScopeInstrumentationTest {
    private val mockExecutionContext: ExecutionContext = mockk(relaxed = true)

    private val fullSchema = createSchema(
        """
        type Query {
            helloWorld: String!
        }
        """
    )

    private val scopedSchema = createSchema(
        """
        type Query {
            helloWorld: String!
        }
        """
    )

    lateinit var contextMocks: ContextMocks

    @BeforeEach
    fun setupMocks() {
        contextMocks = ContextMocks(
            myFullSchema = fullSchema,
            myScopedSchema = scopedSchema,
            myFlagManager = FlagManager.Default,
        )

        clearMocks(mockExecutionContext)
        every { mockExecutionContext.graphQLContext } returns GraphQLContext.newContext().build()
    }

    @Test
    fun `Engine2 execution leaves the context unchanged regardless of its local context`() {
        val input = ExecutionInput.newExecutionInput().query("{ __typename }").build()
        input.setIsEngine2()
        every { mockExecutionContext.graphQLContext } returns input.graphQLContext
        for (localContext in listOf(null, CompositeLocalContext.withContexts(mockk<EngineExecutionContext>()), contextMocks.localContext)) {
            every { mockExecutionContext.getLocalContext<Any>() } returns localContext

            assertSame(
                mockExecutionContext,
                ScopeInstrumentation().instrumentExecutionContext(mockExecutionContext, mockk(), null),
            )
        }
    }

    @Test
    fun `missing Engine1 context fails instead of silently skipping schema scoping`() {
        every { mockExecutionContext.getLocalContext<Any>() } returns CompositeLocalContext.empty
        every { mockExecutionContext.graphQLContext } returns ExecutionInput.newExecutionInput().query("{ __typename }").build().graphQLContext

        val failure = assertThrows<IllegalStateException> {
            ScopeInstrumentation().instrumentExecutionContext(mockExecutionContext, mockk(), null)
        }
        assertEquals("Missing EngineExecutionContextImpl for Engine1 execution", failure.message)
    }

    @Test
    fun `Non introspective query should return full Schema`() {
        mockBasics()
        every { mockExecutionContext.isIntrospective } returns false

        ScopeInstrumentation().instrumentExecutionContext(mockExecutionContext, mockk(), null).let {
            val engineExecutionContext = it.findLocalContextForType<EngineExecutionContextImpl>()
            assertSame(contextMocks.fullSchema, engineExecutionContext.activeSchema)
        }
    }

    @Test
    fun `Introspective query should return scoped Schema`() {
        mockBasics()
        every { mockExecutionContext.isIntrospective } returns true

        ScopeInstrumentation().instrumentExecutionContext(mockExecutionContext, mockk(), null).let {
            val engineExecutionContext = it.findLocalContextForType<EngineExecutionContextImpl>()
            assertEquals(scopedSchema, engineExecutionContext.activeSchema)
        }
    }

    /**
     * Cross-build compatibility test ensuring ScopeInstrumentation extends ViaductInstrumentationBase.
     *
     * This test is critical for OSS/Airbnb compatibility. The Airbnb build requires
     * ScopeInstrumentation to extend ViaductInstrumentationBase for proper dependency injection
     * wrapping in ViaductInstrumentationAdapter.
     *
     * Without this inheritance, OSS changes might compile fine but break the Airbnb build.
     *
     * Historical context: If an OSS developer changed ScopeInstrumentation from extending
     * ViaductInstrumentationBase back to just implementing Instrumentation, it would work
     * in OSS but cause dependency injection failures in Airbnb's build.
     */
    @Test
    @Suppress("USELESS_IS_CHECK")
    fun `verifies ScopeInstrumentation extends ViaductInstrumentationBase for cross-build compatibility`() {
        val scopeInstrumentation = ScopeInstrumentation()
        assertTrue(
            scopeInstrumentation is ViaductInstrumentationBase,
            "ScopeInstrumentation must extend ViaductInstrumentationBase for Airbnb build compatibility. " +
                "This ensures proper wrapping in ViaductInstrumentationAdapter during dependency injection."
        )
    }

    fun mockBasics() {
        every { mockExecutionContext.graphQLSchema } returns contextMocks.engineExecutionContext.activeSchema.schema
        every { mockExecutionContext.getLocalContext<Any>() } returns contextMocks.localContext

        every { mockExecutionContext.transform(any()) } answers {
            val builderConsumer = firstArg<Consumer<ExecutionContextBuilder>>()
            val builder = ExecutionContextBuilder.newExecutionContextBuilder(mockExecutionContext)
            builderConsumer.accept(builder)
            builder.build()
        }
    }
}
