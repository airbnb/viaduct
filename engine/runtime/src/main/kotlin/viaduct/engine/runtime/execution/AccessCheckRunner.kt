package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import java.util.function.Supplier
import viaduct.engine.api.CheckerDispatcher
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.combine
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.runtime.CheckerProxyEngineObjectData
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.execution.FieldExecutionHelpers.resolveVariables
import viaduct.engine.runtime.findLocalContextForType

/**
 * Helper class that holds logic for executing access checks during field resolution
 */
class AccessCheckRunner(
    private val coroutineInterop: CoroutineInterop,
) {
    /**
     * Executes the field access check for the given field.
     *
     * @param parameters The execution parameters containing field and context information
     * @return [Value] of the [CheckerResult] from executing the checker, or [Value] of null if there is no checker
     */
    fun fieldCheck(
        parameters: ExecutionParameters,
        dataFetchingEnvironmentSupplier: Supplier<DataFetchingEnvironment>
    ): Value<out CheckerResult?> {
        val engineExecutionContext = parameters.executionContext.findLocalContextForType<EngineExecutionContextImpl>()
        if (!engineExecutionContext.executeAccessChecksInModstrat) return Value.nullValue

        val field = checkNotNull(parameters.field) { "Expected field to be non-null." }
        val fieldName = field.fieldName
        val parentTypeName = parameters.executionStepInfo.objectType.name
        val checkerDispatcher = engineExecutionContext.dispatcherRegistry.getFieldCheckerDispatcher(parentTypeName, fieldName)
            ?: return Value.nullValue // No access check for this field, return immediately

        // We're fetching an individual field; the current engine result will always be an ObjectEngineResult
        return executeChecker(parameters, dataFetchingEnvironmentSupplier, checkerDispatcher, parameters.parentEngineResult, parameters.executionStepInfo.arguments)
    }

    /**
     * Executes the type access check for the object type represented by [objectEngineResult].
     *
     * @param objectEngineResult The OER for the object type being checked
     * @return [Value] of the [CheckerResult] from executing the checker, or [Value] of null if there is no checker
     */
    fun typeCheck(
        parameters: ExecutionParameters,
        dataFetchingEnvironmentSupplier: Supplier<DataFetchingEnvironment>,
        objectEngineResult: ObjectEngineResultImpl,
        fieldResolutionResult: FieldResolutionResult,
        fieldResolver: FieldResolver
    ): Value<out CheckerResult?> {
        val field = checkNotNull(parameters.field) { "Expected parameters.field to be non-null." }
        val engineExecutionContext = parameters.executionContext.findLocalContextForType<EngineExecutionContextImpl>()
        if (!engineExecutionContext.executeAccessChecksInModstrat) return Value.nullValue

        val typeName = objectEngineResult.graphQLObjectType.name
        val checkerDispatcher = engineExecutionContext.dispatcherRegistry.getTypeCheckerDispatcher(typeName)
            // No access check for this field, return immediately
            ?: return Value.nullValue

        // fetch the selection sets of any child plans for this type
        val fieldTypeChildPlans = field.fieldTypeChildPlans[objectEngineResult.graphQLObjectType] ?: emptyList()
        if (fieldTypeChildPlans.isNotEmpty()) {
            fieldTypeChildPlans.forEach { childPlan ->
                parameters.launchOnRootScope {
                    val variables = resolveVariables(
                        childPlan.variablesResolvers,
                        parameters.executionStepInfo.arguments,
                        parameters.parentEngineResult,
                        parameters.queryEngineResult,
                        engineExecutionContext
                    )
                    val planParameters = parameters.forFieldTypeChildPlan(
                        childPlan,
                        CoercedVariables(variables),
                        fieldResolutionResult
                    )
                    fieldResolver.fetchObject(childPlan.parentType as GraphQLObjectType, planParameters)
                }
            }
        }
        return executeChecker(parameters, dataFetchingEnvironmentSupplier, checkerDispatcher, objectEngineResult, emptyMap())
    }

    /**
     * For a given field with type [fieldType], combines the field [CheckerResult] with the type
     * [CheckerResult] if it exists. Prioritizes errors from field checkers over type checkers.
     *
     * @param fieldResolutionResultValue the value in the raw slot of the field
     * @return [Value] of the combined field and type [CheckerResult]s
     */
    fun combineWithTypeCheck(
        parameters: ExecutionParameters,
        dataFetchingEnvironmentSupplier: Supplier<DataFetchingEnvironment>,
        fieldCheckerResultValue: Value<out CheckerResult?>,
        fieldType: GraphQLOutputType,
        fieldResolutionResultValue: Value<FieldResolutionResult>,
        fieldResolver: FieldResolver
    ): Value<out CheckerResult?> {
        checkNotNull(parameters.field) { "Expected parameters.field to be non-null." }
        val engineExecutionContext = parameters.executionContext.findLocalContextForType<EngineExecutionContextImpl>()
        // Exit early if there is definitely no type check
        if (fieldType !is GraphQLCompositeType ||
            (fieldType is GraphQLObjectType && engineExecutionContext.dispatcherRegistry.getTypeCheckerDispatcher(fieldType.name) == null)
        ) {
            return fieldCheckerResultValue
        }

        return fieldResolutionResultValue.flatMap {
            val engineResult = it.engineResult
            if (engineResult != null) {
                val oer = checkNotNull(engineResult as? ObjectEngineResultImpl) {
                    "Expected engineResult to be instance of ObjectEngineResultImpl, got ${engineResult.javaClass}"
                }
                val typeCheckerResultValue = typeCheck(parameters, dataFetchingEnvironmentSupplier, oer, it, fieldResolver)
                when {
                    typeCheckerResultValue == Value.nullValue -> fieldCheckerResultValue
                    fieldCheckerResultValue == Value.nullValue -> typeCheckerResultValue
                    else -> {
                        // Both checkers exist, combine the CheckerResults
                        fieldCheckerResultValue.flatMap<CheckerResult?> { fieldCheckerResult ->
                            typeCheckerResultValue.flatMap { typeCheckerResult ->
                                check(fieldCheckerResult != null && typeCheckerResult != null) { "Expected non-null field and type checker results" }
                                Value.fromValue(typeCheckerResult.combine(fieldCheckerResult))
                            }
                        }
                    }
                }
            } else {
                // The raw value resolved to null, don't attempt to execute a type check
                fieldCheckerResultValue
            }
        }
    }

    private fun executeChecker(
        parameters: ExecutionParameters,
        dataFetchingEnvironmentSupplier: Supplier<DataFetchingEnvironment>,
        dispatcher: CheckerDispatcher,
        objectEngineResult: ObjectEngineResultImpl,
        arguments: Map<String, Any?>,
    ): Value<out CheckerResult?> {
        val engineExecutionContext = parameters.executionContext.findLocalContextForType<EngineExecutionContextImpl>()
        // Temporary hack to enable shimmed checkers to work on modern engine.
        // See: https://git.musta.ch/airbnb/treehouse/pull/879484 for more details
        val localExecutionContext = engineExecutionContext.copy(
            dataFetchingEnvironment = dataFetchingEnvironmentSupplier.get()
        )
        val instrumentedDispatcher = parameters.instrumentation.instrumentAccessCheck(
            dispatcher,
            InstrumentationExecutionStrategyParameters(parameters.executionContext, parameters.gjParameters),
            parameters.executionContext.instrumentationState
        )

        val deferred = coroutineInterop.scopedAsync {
            val rssMap = instrumentedDispatcher.requiredSelectionSets
            val proxyEODMap = rssMap.mapValues { (_, rss) ->
                val selectionSet = rss?.let {
                    val variables = resolveVariables(
                        it.variablesResolvers,
                        arguments,
                        objectEngineResult,
                        parameters.queryEngineResult,
                        localExecutionContext
                    )
                    localExecutionContext.rawSelectionSetFactory.rawSelectionSet(it.selections, variables)
                }
                CheckerProxyEngineObjectData(
                    objectEngineResult,
                    "missing from checker RSS",
                    selectionSet,
                )
            }
            instrumentedDispatcher.execute(
                arguments,
                proxyEODMap,
                localExecutionContext
            )
        }
        return Value.fromDeferred(deferred)
    }
}
