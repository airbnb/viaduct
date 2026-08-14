package viaduct.engine.api.instrumentation

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.execution.ExecutionContext
import graphql.execution.instrumentation.DocumentAndVariables
import graphql.execution.instrumentation.ExecutionStrategyInstrumentationContext
import graphql.execution.instrumentation.FieldFetchingInstrumentationContext
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.InstrumentationContext
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
import graphql.validation.ValidationError
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.spi.CheckerExecutor

class ViaductModernGJInstrumentationTest {
    @Test
    fun `standard instrumentation lifecycle hooks are delegated`() {
        val delegate = mockk<Instrumentation>()
        val instrumentation = ViaductModernGJInstrumentation.fromStandardInstrumentation(delegate)
        val createStateParameters = mockk<InstrumentationCreateStateParameters>()
        val executionParameters = mockk<InstrumentationExecutionParameters>()
        val validationParameters = mockk<InstrumentationValidationParameters>()
        val strategyParameters = mockk<InstrumentationExecutionStrategyParameters>()
        val operationParameters = mockk<InstrumentationExecuteOperationParameters>()
        val state = mockk<InstrumentationState>()
        val asyncState = mockk<InstrumentationState>()
        val asyncStateFuture = CompletableFuture.completedFuture(asyncState)
        val parseContext = mockk<InstrumentationContext<Document>>()
        val validationContext = mockk<InstrumentationContext<List<ValidationError>>>()
        val strategyContext = mockk<ExecutionStrategyInstrumentationContext>()
        val executionContext = mockk<InstrumentationContext<ExecutionResult>>()
        val operationContext = mockk<InstrumentationContext<ExecutionResult>>()

        every { delegate.createState(createStateParameters) } returns state
        every { delegate.createStateAsync(createStateParameters) } returns asyncStateFuture
        every { delegate.beginParse(executionParameters, state) } returns parseContext
        every { delegate.beginValidation(validationParameters, state) } returns validationContext
        every { delegate.beginExecutionStrategy(strategyParameters, state) } returns strategyContext
        every { delegate.beginExecution(executionParameters, state) } returns executionContext
        every { delegate.beginExecuteOperation(operationParameters, state) } returns operationContext

        assertSame(state, instrumentation.createState(createStateParameters))
        assertSame(asyncStateFuture, instrumentation.createStateAsync(createStateParameters))
        assertSame(parseContext, instrumentation.beginParse(executionParameters, state))
        assertSame(validationContext, instrumentation.beginValidation(validationParameters, state))
        assertSame(strategyContext, instrumentation.beginExecutionStrategy(strategyParameters, state))
        assertSame(executionContext, instrumentation.beginExecution(executionParameters, state))
        assertSame(operationContext, instrumentation.beginExecuteOperation(operationParameters, state))

        verify(exactly = 1) {
            delegate.createState(createStateParameters)
            delegate.createStateAsync(createStateParameters)
            delegate.beginParse(executionParameters, state)
            delegate.beginValidation(validationParameters, state)
            delegate.beginExecutionStrategy(strategyParameters, state)
            delegate.beginExecution(executionParameters, state)
            delegate.beginExecuteOperation(operationParameters, state)
        }
    }

    @Test
    fun `standard instrumentation field hooks are delegated`() {
        val delegate = mockk<Instrumentation>()
        val instrumentation = ViaductModernGJInstrumentation.fromStandardInstrumentation(delegate)
        val state = mockk<InstrumentationState>()
        val fieldParameters = mockk<InstrumentationFieldParameters>()
        val fetchParameters = mockk<InstrumentationFieldFetchParameters>()
        val completionParameters = mockk<InstrumentationFieldCompleteParameters>()
        val executionContext = mockk<InstrumentationContext<Any>>()
        val fetchingContext = mockk<FieldFetchingInstrumentationContext>()
        val completionContext = mockk<InstrumentationContext<Any>>()
        val listCompletionContext = mockk<InstrumentationContext<Any>>()

        every { delegate.beginFieldExecution(fieldParameters, state) } returns executionContext
        every { delegate.beginFieldFetching(fetchParameters, state) } returns fetchingContext
        every { delegate.beginFieldCompletion(completionParameters, state) } returns completionContext
        every { delegate.beginFieldListCompletion(completionParameters, state) } returns listCompletionContext

        assertSame(executionContext, instrumentation.beginFieldExecution(fieldParameters, state))
        assertSame(fetchingContext, instrumentation.beginFieldFetching(fetchParameters, state))
        assertSame(completionContext, instrumentation.beginFieldCompletion(completionParameters, state))
        assertSame(listCompletionContext, instrumentation.beginFieldListCompletion(completionParameters, state))

        verify(exactly = 1) {
            delegate.beginFieldExecution(fieldParameters, state)
            delegate.beginFieldFetching(fetchParameters, state)
            delegate.beginFieldCompletion(completionParameters, state)
            delegate.beginFieldListCompletion(completionParameters, state)
        }
    }

    @Test
    fun `standard instrumentation transformations are delegated`() {
        val delegate = mockk<Instrumentation>()
        val instrumentation = ViaductModernGJInstrumentation.fromStandardInstrumentation(delegate)
        val state = mockk<InstrumentationState>()
        val executionParameters = mockk<InstrumentationExecutionParameters>()
        val fetchParameters = mockk<InstrumentationFieldFetchParameters>()
        val documentAndVariables = mockk<DocumentAndVariables>()
        val transformedDocumentAndVariables = mockk<DocumentAndVariables>()
        val dataFetcher = mockk<DataFetcher<Any>>()
        val transformedDataFetcher = mockk<DataFetcher<Any>>()
        val executionContext = mockk<ExecutionContext>()
        val transformedExecutionContext = mockk<ExecutionContext>()
        val executionInput = mockk<ExecutionInput>()
        val transformedExecutionInput = mockk<ExecutionInput>()
        val executionResult = mockk<ExecutionResult>()
        val transformedExecutionResult = mockk<ExecutionResult>()
        val transformedExecutionResultFuture = CompletableFuture.completedFuture(transformedExecutionResult)
        val schema = mockk<GraphQLSchema>()
        val transformedSchema = mockk<GraphQLSchema>()

        every {
            delegate.instrumentDocumentAndVariables(documentAndVariables, executionParameters, state)
        } returns transformedDocumentAndVariables
        every { delegate.instrumentDataFetcher(dataFetcher, fetchParameters, state) } returns transformedDataFetcher
        every {
            delegate.instrumentExecutionContext(executionContext, executionParameters, state)
        } returns transformedExecutionContext
        every {
            delegate.instrumentExecutionInput(executionInput, executionParameters, state)
        } returns transformedExecutionInput
        every {
            delegate.instrumentExecutionResult(executionResult, executionParameters, state)
        } returns transformedExecutionResultFuture
        every { delegate.instrumentSchema(schema, executionParameters, state) } returns transformedSchema

        assertSame(
            transformedDocumentAndVariables,
            instrumentation.instrumentDocumentAndVariables(documentAndVariables, executionParameters, state),
        )
        assertSame(
            transformedDataFetcher,
            instrumentation.instrumentDataFetcher(dataFetcher, fetchParameters, state),
        )
        assertSame(
            transformedExecutionContext,
            instrumentation.instrumentExecutionContext(executionContext, executionParameters, state),
        )
        assertSame(
            transformedExecutionInput,
            instrumentation.instrumentExecutionInput(executionInput, executionParameters, state),
        )
        assertSame(
            transformedExecutionResultFuture,
            instrumentation.instrumentExecutionResult(executionResult, executionParameters, state),
        )
        assertSame(transformedSchema, instrumentation.instrumentSchema(schema, executionParameters, state))

        verify(exactly = 1) {
            delegate.instrumentDocumentAndVariables(documentAndVariables, executionParameters, state)
            delegate.instrumentDataFetcher(dataFetcher, fetchParameters, state)
            delegate.instrumentExecutionContext(executionContext, executionParameters, state)
            delegate.instrumentExecutionInput(executionInput, executionParameters, state)
            delegate.instrumentExecutionResult(executionResult, executionParameters, state)
            delegate.instrumentSchema(schema, executionParameters, state)
        }
    }

    @Test
    fun `Viaduct hooks use safe defaults`() {
        val instrumentation =
            ViaductModernGJInstrumentation.fromStandardInstrumentation(mockk(relaxed = true))
        val strategyParameters = mockk<InstrumentationExecutionStrategyParameters>()
        val state = mockk<InstrumentationState>()
        val checkerExecutor = mockk<CheckerExecutor>()
        val dataFetchingEnvironment = mockk<DataFetchingEnvironment>()
        val nodeFetchingParameters =
            InstrumentNodeFetchingParameters(
                requiredBy = null,
                resolverMetadata = null,
            )

        assertNotNull(instrumentation.beginFetchObject(strategyParameters, state))
        assertNotNull(instrumentation.beginCompleteObject(strategyParameters, state))
        assertSame(
            checkerExecutor,
            instrumentation.instrumentAccessCheck(
                checkerExecutor,
                dataFetchingEnvironment,
                strategyParameters,
                state,
            ),
        )
        assertNotNull(instrumentation.beginNodeFetching(nodeFetchingParameters, state))
    }
}
