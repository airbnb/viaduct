package viaduct.engine.runtime.instrumentation

import graphql.ExecutionInput
import graphql.ExecutionResultImpl
import graphql.GraphqlErrorBuilder
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStepInfo
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.language.OperationDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TaggedMetricInstrumentationTest {
    @Nested
    inner class ExecutionTest {
        val simpleMeterRegistry = SimpleMeterRegistry()
        val subject = TaggedMetricInstrumentation(
            meterRegistry = simpleMeterRegistry
        )
        val state = mockk<InstrumentationState>()
        val params = mockk<InstrumentationExecutionParameters>()
        val input = mockk<ExecutionInput>()

        @BeforeEach
        fun setup() {
            every { params.executionInput } returns input
            every { input.operationName } returns "testOperation"
        }

        @Test
        fun `test beginExecute should emit metric`() {
            val ctx = subject.beginExecution(params, state)
            checkNotNull(ctx) { "context should not be null" }

            ctx.onDispatched()
            ctx.onCompleted(ExecutionResultImpl.newExecutionResult().data(123).build(), null)
            val meter = simpleMeterRegistry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_EXECUTION_METER_NAME }
            assertNotNull(meter)
            val tagsMap = meter?.id?.tags?.associate { it.key to it.value } ?: emptyMap()
            assertEquals("testOperation", tagsMap["operation_name"])
            assertEquals("true", tagsMap["success"])
        }

        @Test
        fun `test execution with exception should emit metric with success = false tag`() {
            val ctx = subject.beginExecution(params, state)
            checkNotNull(ctx) { "context should not be null" }

            ctx.onDispatched()
            ctx.onCompleted(null, RuntimeException("test exception"))
            val meter = simpleMeterRegistry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_EXECUTION_METER_NAME }
            assertNotNull(meter)
            val tagsMap = meter?.id?.tags?.associate { it.key to it.value } ?: emptyMap()
            assertEquals("testOperation", tagsMap["operation_name"])
            assertEquals("false", tagsMap["success"])
        }

        @Test
        fun `test execution without data should emit metric with success = false tag`() {
            val ctx = subject.beginExecution(params, state)
            checkNotNull(ctx) { "context should not be null" }

            ctx.onDispatched()
            ctx.onCompleted(
                ExecutionResultImpl.newExecutionResult()
                    .addError(
                        GraphqlErrorBuilder.newError()
                            .message("some error").build()
                    )
                    .build(),
                null
            )
            val meter = simpleMeterRegistry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_EXECUTION_METER_NAME }
            assertNotNull(meter)
            val tagsMap = meter?.id?.tags?.associate { it.key to it.value } ?: emptyMap()
            assertEquals("testOperation", tagsMap["operation_name"])
            assertEquals("false", tagsMap["success"])
        }
    }

    @Nested
    inner class OperationTest {
        val simpleMeterRegistry = SimpleMeterRegistry()
        val subject = TaggedMetricInstrumentation(
            meterRegistry = simpleMeterRegistry
        )
        val state = mockk<InstrumentationState>()
        val params = mockk<InstrumentationExecuteOperationParameters>()
        val executionContext = mockk<ExecutionContext>()
        val operationDefinition = mockk<OperationDefinition>()

        @BeforeEach
        fun setup() {
            every { params.executionContext } returns executionContext
            every { executionContext.operationDefinition } returns operationDefinition
            every { operationDefinition.name } returns "testOperation"
        }

        @Test
        fun `test beginExecuteOperation should emit metric with success tag`() {
            val ctx = subject.beginExecuteOperation(params, state)
            checkNotNull(ctx) { "context should not be null" }

            ctx.onDispatched()
            ctx.onCompleted(ExecutionResultImpl.newExecutionResult().data(123).build(), null)
            val meter = simpleMeterRegistry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_OPERATION_METER_NAME }
            assertNotNull(meter)
            val tagsMap = meter?.id?.tags?.associate { it.key to it.value } ?: emptyMap()
            assertEquals("testOperation", tagsMap["operation_name"])
            assertEquals("true", tagsMap["success"])
        }

        @Test
        fun `test executeOperation with exception should emit metric with success = false tag`() {
            val ctx = subject.beginExecuteOperation(params, state)
            checkNotNull(ctx) { "context should not be null" }

            ctx.onDispatched()
            ctx.onCompleted(null, RuntimeException("test exception"))
            val meter = simpleMeterRegistry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_OPERATION_METER_NAME }
            assertNotNull(meter)
            val tagsMap = meter?.id?.tags?.associate { it.key to it.value } ?: emptyMap()
            assertEquals("testOperation", tagsMap["operation_name"])
            assertEquals("false", tagsMap["success"])
        }

        @Test
        fun `test executeOperation without data should emit metric with success = false tag`() {
            val ctx = subject.beginExecuteOperation(params, state)
            checkNotNull(ctx) { "context should not be null" }

            ctx.onDispatched()
            ctx.onCompleted(
                ExecutionResultImpl.newExecutionResult()
                    .addError(
                        GraphqlErrorBuilder.newError()
                            .message("some error").build()
                    )
                    .build(),
                null
            )
            val meter = simpleMeterRegistry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_OPERATION_METER_NAME }
            assertNotNull(meter)
            val tagsMap = meter?.id?.tags?.associate { it.key to it.value } ?: emptyMap()
            assertEquals("testOperation", tagsMap["operation_name"])
            assertEquals("false", tagsMap["success"])
        }
    }

    @Test
    fun `test field with tags`() {
        val simpleMeterRegistry = SimpleMeterRegistry()
        val subject = TaggedMetricInstrumentation(
            meterRegistry = simpleMeterRegistry
        )
        val state = mockk<InstrumentationState>()
        val params = mockk<InstrumentationFieldFetchParameters>()
        val executionContext = mockk<ExecutionContext>()
        val field = mockk<GraphQLFieldDefinition>()
        val objectType = mockk<GraphQLObjectType>()
        val executionStepInfo = mockk<ExecutionStepInfo>()
        val parentStepInfo = mockk<ExecutionStepInfo>()
        every { executionContext.operationDefinition } returns OperationDefinition.newOperationDefinition().name("testOperation").build()
        every { params.executionContext } returns executionContext
        every { params.executionStepInfo } returns executionStepInfo
        every { executionStepInfo.parent } returns parentStepInfo
        every { parentStepInfo.type } returns objectType
        every { objectType.name } returns "foo"
        every { params.field } returns field
        every { field.name } returns "bar"

        val ctx = subject.beginFieldFetch(params, state)
        checkNotNull(ctx) { "context should not be null" }

        ctx.onDispatched()
        ctx.onCompleted(123, null)
        val meter = simpleMeterRegistry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_FIELD_METER_NAME }
        assertNotNull(meter)

        val tagsMap = meter?.id?.tags?.associate { it.key to it.value } ?: emptyMap()
        assertEquals("testOperation", tagsMap["operation_name"])
        assertEquals("true", tagsMap["success"])
        assertEquals("foo.bar", tagsMap["field"])
    }
}
