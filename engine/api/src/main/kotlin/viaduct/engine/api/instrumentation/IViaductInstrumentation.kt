package viaduct.engine.api.instrumentation

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.execution.ExecutionContext
import graphql.execution.instrumentation.DocumentAndVariables
import graphql.execution.instrumentation.ExecutionStrategyInstrumentationContext
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
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
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLSchema
import graphql.validation.ValidationError
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import viaduct.engine.api.CheckerExecutor

/**
 * An optimized instrumentation interface for Viaduct. Provides specialized interface methods that
 * are executed synchronously in every field, allowing OptimizedChainedInstrumentation to only run instrumentations
 * that are needed and optimize the chained instrumentation loop.
 *
 * Use `asStandardInstrumentation`/`asStandardInstrumentations` defined below to convert to a standard
 * Instrumentation class.
 */
open class ViaductInstrumentationBase : IViaductInstrumentation {
    fun asStandardInstrumentation() = asStandardInstrumentation

    open val asStandardInstrumentation by lazy { ViaductInstrumentationAdapter(this) }
}

fun List<ViaductInstrumentationBase>.asStandardInstrumentations(): List<ViaductModernGJInstrumentation> = this.map { it.asStandardInstrumentation() }

interface IViaductInstrumentation {
    val default: Instrumentation
        get() = SimplePerformantInstrumentation.INSTANCE

    fun createState(parameters: InstrumentationCreateStateParameters): InstrumentationState? = null

    fun beginExecution(
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): InstrumentationContext<ExecutionResult>? = default.beginExecution(parameters, state)

    fun beginParse(
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Document?>? = default.beginParse(parameters, state)

    fun beginValidation(
        parameters: InstrumentationValidationParameters,
        state: InstrumentationState?
    ): InstrumentationContext<MutableList<ValidationError>>? = default.beginValidation(parameters, state)

    fun beginExecuteOperation(
        parameters: InstrumentationExecuteOperationParameters,
        state: InstrumentationState?
    ): InstrumentationContext<ExecutionResult>? = default.beginExecuteOperation(parameters, state)

    fun beginExecutionStrategy(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): ExecutionStrategyInstrumentationContext? = default.beginExecutionStrategy(parameters, state)

    fun beginSubscribedFieldEvent(
        parameters: InstrumentationFieldParameters,
        state: InstrumentationState?
    ): InstrumentationContext<ExecutionResult>? = default.beginSubscribedFieldEvent(parameters, state)

    fun instrumentExecutionInput(
        executionInput: ExecutionInput,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): ExecutionInput = default.instrumentExecutionInput(executionInput, parameters, state)

    fun instrumentDocumentAndVariables(
        documentAndVariables: DocumentAndVariables,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): DocumentAndVariables = default.instrumentDocumentAndVariables(documentAndVariables, parameters, state)

    fun instrumentSchema(
        schema: GraphQLSchema,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): GraphQLSchema = default.instrumentSchema(schema, parameters, state)

    fun instrumentExecutionContext(
        executionContext: ExecutionContext,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): ExecutionContext = default.instrumentExecutionContext(executionContext, parameters, state)

    fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): CompletableFuture<ExecutionResult> = default.instrumentExecutionResult(executionResult, parameters, state)

    interface WithBeginFieldExecution : IViaductInstrumentation {
        fun beginFieldExecution(
            parameters: InstrumentationFieldParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>?
    }

    interface WithBeginFieldFetch : IViaductInstrumentation {
        fun beginFieldFetch(
            parameters: InstrumentationFieldFetchParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>?
    }

    interface WithBeginFieldCompletion : IViaductInstrumentation {
        fun beginFieldCompletion(
            parameters: InstrumentationFieldCompleteParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>?
    }

    interface WithBeginFieldListCompletion : IViaductInstrumentation {
        fun beginFieldListCompletion(
            parameters: InstrumentationFieldCompleteParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>?
    }

    interface WithInstrumentDataFetcher : IViaductInstrumentation {
        fun instrumentDataFetcher(
            dataFetcher: DataFetcher<*>,
            parameters: InstrumentationFieldFetchParameters,
            state: InstrumentationState?
        ): DataFetcher<*>

        fun transformResult(
            dataFetcher: DataFetcher<*>,
            transform: DataFetchingEnvironment.(Any?) -> Any?
        ): DataFetcher<*> {
            return DataFetcher { env ->

                val result = dataFetcher.get(env)
                if (result !is CompletionStage<*>) {
                    return@DataFetcher env.transform(result)
                }

                result.thenCompose {
                    @Suppress("UNCHECKED_CAST")
                    when (val transformed = env.transform(it)) {
                        is CompletionStage<*> -> transformed as CompletionStage<Any?>
                        else -> CompletableFuture.completedFuture(transformed)
                    }
                }
            }
        }
    }

    interface WithBeginFetchObject : IViaductInstrumentation {
        fun beginFetchObject(
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Unit>
    }

    interface WithBeginCompleteObject : IViaductInstrumentation {
        fun beginCompleteObject(
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any>
    }

    interface WithInstrumentAccessCheck : IViaductInstrumentation {
        fun instrumentAccessCheck(
            checkerExecutor: CheckerExecutor,
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): CheckerExecutor
    }

    companion object {
        @JvmStatic
        fun asStandardInstrumentations(viaductInstrumentations: List<ViaductInstrumentationBase>) = viaductInstrumentations.asStandardInstrumentations()
    }
}
