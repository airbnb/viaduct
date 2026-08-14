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
import graphql.execution.instrumentation.SimpleInstrumentationContext.noOp
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
import java.util.concurrent.CompletableFuture
import viaduct.engine.api.spi.CheckerExecutor

/**
 * A GraphQL Java [Instrumentation] with the additional lifecycle hooks used by Viaduct's
 * execution strategy.
 */
interface ViaductModernGJInstrumentation : Instrumentation {
    companion object {
        fun fromStandardInstrumentation(stdInstrumentation: Instrumentation) =
            object : ViaductModernGJInstrumentation {
                override fun beginFetchObject(
                    parameters: InstrumentationExecutionStrategyParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Unit> {
                    return noOp()
                }

                override fun beginCompleteObject(
                    parameters: InstrumentationExecutionStrategyParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Any> {
                    return noOp()
                }

                override fun createState(parameters: InstrumentationCreateStateParameters): InstrumentationState? {
                    return stdInstrumentation.createState(parameters)
                }

                override fun createStateAsync(parameters: InstrumentationCreateStateParameters): CompletableFuture<InstrumentationState>? {
                    return stdInstrumentation.createStateAsync(parameters)
                }

                override fun beginParse(
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Document>? {
                    return stdInstrumentation.beginParse(parameters, state)
                }

                override fun beginValidation(
                    parameters: InstrumentationValidationParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<List<ValidationError>>? {
                    return stdInstrumentation.beginValidation(parameters, state)
                }

                override fun beginExecutionStrategy(
                    parameters: InstrumentationExecutionStrategyParameters,
                    state: InstrumentationState?
                ): ExecutionStrategyInstrumentationContext? {
                    return stdInstrumentation.beginExecutionStrategy(parameters, state)
                }

                override fun beginExecution(
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<ExecutionResult>? {
                    return stdInstrumentation.beginExecution(parameters, state)
                }

                override fun beginExecuteOperation(
                    parameters: InstrumentationExecuteOperationParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<ExecutionResult>? {
                    return stdInstrumentation.beginExecuteOperation(parameters, state)
                }

                override fun beginFieldExecution(
                    parameters: InstrumentationFieldParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Any>? {
                    return stdInstrumentation.beginFieldExecution(parameters, state)
                }

                override fun beginFieldFetching(
                    parameters: InstrumentationFieldFetchParameters,
                    state: InstrumentationState?
                ): FieldFetchingInstrumentationContext? {
                    return stdInstrumentation.beginFieldFetching(parameters, state)
                }

                override fun beginFieldCompletion(
                    parameters: InstrumentationFieldCompleteParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Any>? {
                    return stdInstrumentation.beginFieldCompletion(parameters, state)
                }

                override fun beginFieldListCompletion(
                    parameters: InstrumentationFieldCompleteParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Any>? {
                    return stdInstrumentation.beginFieldListCompletion(parameters, state)
                }

                override fun instrumentDocumentAndVariables(
                    documentAndVariables: DocumentAndVariables,
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): DocumentAndVariables {
                    return stdInstrumentation.instrumentDocumentAndVariables(documentAndVariables, parameters, state)
                }

                override fun instrumentDataFetcher(
                    dataFetcher: DataFetcher<*>,
                    parameters: InstrumentationFieldFetchParameters,
                    state: InstrumentationState?
                ): DataFetcher<*> {
                    return stdInstrumentation.instrumentDataFetcher(dataFetcher, parameters, state)
                }

                override fun instrumentExecutionContext(
                    executionContext: ExecutionContext,
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): ExecutionContext {
                    return stdInstrumentation.instrumentExecutionContext(executionContext, parameters, state)
                }

                override fun instrumentExecutionInput(
                    executionInput: ExecutionInput,
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): ExecutionInput {
                    return stdInstrumentation.instrumentExecutionInput(executionInput, parameters, state)
                }

                override fun instrumentExecutionResult(
                    executionResult: ExecutionResult,
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): CompletableFuture<ExecutionResult> {
                    return stdInstrumentation.instrumentExecutionResult(executionResult, parameters, state)
                }

                override fun instrumentSchema(
                    schema: GraphQLSchema,
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): GraphQLSchema {
                    return stdInstrumentation.instrumentSchema(schema, parameters, state)
                }
            }
    }

    fun beginFetchObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Unit> {
        return noOp()
    }

    fun beginCompleteObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any> {
        return noOp()
    }

    fun instrumentAccessCheck(
        checkerExecutor: CheckerExecutor,
        dataFetchingEnvironment: DataFetchingEnvironment,
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): CheckerExecutor {
        return checkerExecutor
    }

    fun beginNodeFetching(
        parameters: InstrumentNodeFetchingParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? {
        return noOp()
    }
}
