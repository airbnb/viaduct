package viaduct.engine.api.instrumentation

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.execution.ExecutionContext
import graphql.execution.instrumentation.DocumentAndVariables
import graphql.execution.instrumentation.ExecuteObjectInstrumentationContext
import graphql.execution.instrumentation.ExecutionStrategyInstrumentationContext
import graphql.execution.instrumentation.FieldFetchingInstrumentationContext
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext.noOp
import graphql.execution.instrumentation.SimplePerformantInstrumentation
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
import graphql.schema.GraphQLSchema
import graphql.validation.ValidationError
import java.util.concurrent.CompletableFuture
import viaduct.engine.api.CheckerExecutor

/**
 * ViaductModernInstrumentation is an interface representing the instrumentation methods that are available in
 * Viaduct's execution strategy.
 */
interface ViaductModernInstrumentation {
    companion object {
        fun asGJInstrumentation(viaductInstrumentation: ViaductModernInstrumentation): ViaductModernGJInstrumentation {
            return object : ViaductModernGJInstrumentation {
                override fun createState(parameters: InstrumentationCreateStateParameters): InstrumentationState? {
                    return viaductInstrumentation.createState(parameters)
                }

                override fun createStateAsync(parameters: InstrumentationCreateStateParameters): CompletableFuture<InstrumentationState>? {
                    return viaductInstrumentation.createStateAsync(parameters)
                }

                override fun beginParse(
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Document>? {
                    return viaductInstrumentation.beginParse(parameters, state)
                }

                override fun beginValidation(
                    parameters: InstrumentationValidationParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<List<ValidationError>>? {
                    return viaductInstrumentation.beginValidation(parameters, state)
                }

                override fun beginExecutionStrategy(
                    parameters: InstrumentationExecutionStrategyParameters,
                    state: InstrumentationState?
                ): ExecutionStrategyInstrumentationContext? {
                    return viaductInstrumentation.beginExecutionStrategy(parameters, state)
                }

                override fun beginExecution(
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<ExecutionResult>? {
                    return viaductInstrumentation.beginExecution(parameters, state)
                }

                override fun beginExecuteOperation(
                    parameters: InstrumentationExecuteOperationParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<ExecutionResult>? {
                    return viaductInstrumentation.beginExecuteOperation(parameters, state)
                }

                override fun beginFetchObject(
                    parameters: InstrumentationExecutionStrategyParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Unit> {
                    if (viaductInstrumentation is WithBeginFetchObject) {
                        return viaductInstrumentation.beginFetchObject(parameters, state)
                    }
                    return noOp()
                }

                override fun beginFieldExecution(
                    parameters: InstrumentationFieldParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Any>? {
                    if (viaductInstrumentation is WithBeginFieldExecution) {
                        return viaductInstrumentation.beginFieldExecution(parameters, state)
                    }
                    return noOp()
                }

                override fun beginFieldFetching(
                    parameters: InstrumentationFieldFetchParameters,
                    state: InstrumentationState?
                ): FieldFetchingInstrumentationContext? {
                    if (viaductInstrumentation is WithBeginFieldFetching) {
                        return viaductInstrumentation.beginFieldFetching(parameters, state)
                    }
                    return FieldFetchingInstrumentationContext.NOOP
                }

                override fun beginCompleteObject(
                    parameters: InstrumentationExecutionStrategyParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Any> {
                    if (viaductInstrumentation is WithBeginCompleteObject) {
                        return viaductInstrumentation.beginCompleteObject(parameters, state)
                    }
                    return noOp()
                }

                override fun instrumentAccessCheck(
                    checkerExecutor: CheckerExecutor,
                    parameters: InstrumentationExecutionStrategyParameters,
                    state: InstrumentationState?
                ): CheckerExecutor {
                    if (viaductInstrumentation is WithInstrumentAccessCheck) {
                        return viaductInstrumentation.instrumentAccessCheck(checkerExecutor, parameters, state)
                    }
                    return checkerExecutor
                }

                override fun beginFieldCompletion(
                    parameters: InstrumentationFieldCompleteParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Any>? {
                    if (viaductInstrumentation is WithBeginFieldCompletion) {
                        return viaductInstrumentation.beginFieldCompletion(parameters, state)
                    }
                    return noOp()
                }

                override fun beginFieldListCompletion(
                    parameters: InstrumentationFieldCompleteParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<Any>? {
                    if (viaductInstrumentation is WithBeginFieldListCompletion) {
                        return viaductInstrumentation.beginFieldListCompletion(parameters, state)
                    }
                    return noOp()
                }

                override fun instrumentDocumentAndVariables(
                    documentAndVariables: DocumentAndVariables,
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): DocumentAndVariables {
                    return viaductInstrumentation.instrumentDocumentAndVariables(documentAndVariables, parameters, state)
                }

                override fun instrumentDataFetcher(
                    dataFetcher: DataFetcher<*>,
                    parameters: InstrumentationFieldFetchParameters,
                    state: InstrumentationState?
                ): DataFetcher<*> {
                    if (viaductInstrumentation is WithInstrumentDataFetcher) {
                        return viaductInstrumentation.instrumentDataFetcher(dataFetcher, parameters, state)
                    }
                    return dataFetcher
                }

                override fun instrumentExecutionContext(
                    executionContext: ExecutionContext,
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): ExecutionContext {
                    return viaductInstrumentation.instrumentExecutionContext(executionContext, parameters, state)
                }

                override fun instrumentExecutionInput(
                    executionInput: ExecutionInput,
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): ExecutionInput {
                    return viaductInstrumentation.instrumentExecutionInput(executionInput, parameters, state)
                }

                override fun instrumentExecutionResult(
                    executionResult: ExecutionResult,
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): CompletableFuture<ExecutionResult> {
                    return viaductInstrumentation.instrumentExecutionResult(executionResult, parameters, state)
                }

                override fun instrumentSchema(
                    schema: GraphQLSchema,
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): GraphQLSchema {
                    return viaductInstrumentation.instrumentSchema(schema, parameters, state)
                }

                // ---
                // Unimplemented in Viaduct Modern
                // ---
                override fun beginExecuteObject(
                    parameters: InstrumentationExecutionStrategyParameters?,
                    state: InstrumentationState?
                ): ExecuteObjectInstrumentationContext? {
                    return ExecuteObjectInstrumentationContext.NOOP
                }

                override fun beginDeferredField(
                    parameters: InstrumentationFieldParameters?,
                    state: InstrumentationState?
                ): InstrumentationContext<Any> {
                    return noOp()
                }

                override fun beginSubscribedFieldEvent(
                    parameters: InstrumentationFieldParameters,
                    state: InstrumentationState?
                ): InstrumentationContext<ExecutionResult>? {
                    return noOp()
                }
            }
        }
    }

    private val default: Instrumentation
        get() = SimplePerformantInstrumentation.INSTANCE

    fun asGJInstrumentation(): ViaductModernGJInstrumentation {
        return asGJInstrumentation(this)
    }

    fun createState(parameters: InstrumentationCreateStateParameters): InstrumentationState? {
        return default.createState(parameters)
    }

    fun createStateAsync(parameters: InstrumentationCreateStateParameters): CompletableFuture<InstrumentationState>? {
        return default.createStateAsync(parameters)
    }

    fun beginParse(
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Document>? {
        return default.beginParse(parameters, state)
    }

    fun beginValidation(
        parameters: InstrumentationValidationParameters,
        state: InstrumentationState?
    ): InstrumentationContext<List<ValidationError>>? {
        return default.beginValidation(parameters, state)
    }

    fun beginExecutionStrategy(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): ExecutionStrategyInstrumentationContext? {
        return default.beginExecutionStrategy(parameters, state)
    }

    fun beginExecution(
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): InstrumentationContext<ExecutionResult>? {
        return default.beginExecution(parameters, state)
    }

    fun beginExecuteOperation(
        parameters: InstrumentationExecuteOperationParameters,
        state: InstrumentationState?
    ): InstrumentationContext<ExecutionResult>? {
        return default.beginExecuteOperation(parameters, state)
    }

    // Execution Strategy instrumentation

    interface WithBeginFetchObject : ViaductModernInstrumentation {
        fun beginFetchObject(
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Unit>
    }

    interface WithBeginFieldExecution : ViaductModernInstrumentation {
        fun beginFieldExecution(
            parameters: InstrumentationFieldParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>
    }

    interface WithBeginFieldFetching : ViaductModernInstrumentation {
        fun beginFieldFetching(
            parameters: InstrumentationFieldFetchParameters,
            state: InstrumentationState?
        ): FieldFetchingInstrumentationContext?
    }

    interface WithBeginCompleteObject : ViaductModernInstrumentation {
        fun beginCompleteObject(
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>
    }

    interface WithBeginFieldCompletion : ViaductModernInstrumentation {
        fun beginFieldCompletion(
            parameters: InstrumentationFieldCompleteParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>?
    }

    interface WithBeginFieldListCompletion : ViaductModernInstrumentation {
        fun beginFieldListCompletion(
            parameters: InstrumentationFieldCompleteParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>?
    }

    // End Execution Strategy instrumentation

    interface WithInstrumentDataFetcher : ViaductModernInstrumentation {
        fun instrumentDataFetcher(
            dataFetcher: DataFetcher<*>,
            parameters: InstrumentationFieldFetchParameters,
            state: InstrumentationState?
        ): DataFetcher<*>
    }

    interface WithInstrumentAccessCheck : ViaductModernInstrumentation {
        fun instrumentAccessCheck(
            checkerExecutor: CheckerExecutor,
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): CheckerExecutor
    }

    fun instrumentDocumentAndVariables(
        documentAndVariables: DocumentAndVariables,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): DocumentAndVariables {
        return default.instrumentDocumentAndVariables(documentAndVariables, parameters, state)
    }

    fun instrumentExecutionContext(
        executionContext: ExecutionContext,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): ExecutionContext {
        return default.instrumentExecutionContext(executionContext, parameters, state)
    }

    fun instrumentExecutionInput(
        executionInput: ExecutionInput,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): ExecutionInput {
        return default.instrumentExecutionInput(executionInput, parameters, state)
    }

    fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): CompletableFuture<ExecutionResult> {
        return default.instrumentExecutionResult(executionResult, parameters, state)
    }

    fun instrumentSchema(
        schema: GraphQLSchema,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): GraphQLSchema {
        return default.instrumentSchema(schema, parameters, state)
    }
}

/**
 * ViaductModernGJInstrumentation is an implementation of GraphQL Java's Instrumentation interface that
 * contains the additional methods required by ViaductModernInstrumentation.
 *
 * This allows us to adapt GraphQL Java's Instrumentation to an instrumentation that can be used inside Viaduct's
 * execution strategy.
 */
interface ViaductModernGJInstrumentation : Instrumentation {
    companion object {
        // Adapt a standard GraphQL-Java Instrumentation to a ViaductModernGJInstrumentation
        fun fromStandardInstrumentation(stdInstrumentation: Instrumentation) =
            object : ViaductModernGJInstrumentation {
                // make the two ModernGJInstrumentation methods noops
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

                // Delegate all the other methods...
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

    // Modern-only methods that are not part of the standard GraphQL Java Instrumentation interface, but for instrumentation that is running
    // on both modern + classic, we may want to have instrumentation running on both.

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
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): CheckerExecutor {
        return checkerExecutor
    }
}
