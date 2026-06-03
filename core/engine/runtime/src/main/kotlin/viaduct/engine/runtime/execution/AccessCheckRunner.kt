@file:Suppress("DEPRECATION") // CoroutineInterop retained for Airbnb

package viaduct.engine.runtime.execution

import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import java.util.function.Supplier
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.combine
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.CheckerDispatcher
import viaduct.engine.runtime.CheckerProxyEngineObjectData
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.EngineExecutionContextExtensions.dispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextExtensions.executeAccessChecksInModstrat
import viaduct.engine.runtime.EngineExecutionContextExtensions.isResolverSelective
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.execution.FieldExecutionHelpers.resolveRSSVariables
import viaduct.utils.slf4j.ifDebug
import viaduct.utils.slf4j.logger

/**
 * Helper class that holds logic for executing access checks during field resolution
 */
class AccessCheckRunner(
    private val coroutineInterop: CoroutineInterop,
) {
    companion object {
        private val log by logger()
    }

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
        val engineExecutionContext = parameters.engineExecutionContext
        if (!engineExecutionContext.executeAccessChecksInModstrat) return Value.nullValue

        val field = checkNotNull(parameters.field) { "Expected field to be non-null." }
        val fieldName = field.fieldName
        val parentTypeName = parameters.executionStepInfo.objectType.name
        val checkerDispatcher = engineExecutionContext.dispatcherRegistry.getFieldCheckerDispatcher(parentTypeName, fieldName)
            ?: return Value.nullValue // No access check for this field, return immediately

        return executeChecker(
            parameters,
            dataFetchingEnvironmentSupplier,
            checkerDispatcher,
            parameters.parentEngineResult,
            parameters.executionStepInfo.arguments,
            CheckerExecutor.CheckerType.FIELD,
        )
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
        val engineExecutionContext = parameters.engineExecutionContext
        if (!engineExecutionContext.executeAccessChecksInModstrat) return Value.nullValue

        val typeName = objectEngineResult.type.name
        val checkerDispatcher = engineExecutionContext.dispatcherRegistry.getTypeCheckerDispatcher(typeName)
            // No access check for this field, return immediately
            ?: return Value.nullValue

        // fetch the selection sets of any child plans for this type
        val fieldTypeChildPlans = field.fieldTypeChildPlans[objectEngineResult.type]?.value ?: emptyList()
        val typeCheckParameters = if (fieldTypeChildPlans.isEmpty()) {
            parameters
        } else {
            // QueryPlanIndex is used to convert RequiredSelectionSet's into a
            // representation that can be used for OER keys. For perf reasons,
            // QPI does not index fieldTypeChildPlans, so we do that here.
            parameters.copy(
                queryPlanIndex = fieldTypeChildPlans.fold(parameters.queryPlanIndex) { index, plan ->
                    index.merge(parameters.queryPlanIndexFactory.create(plan))
                }
            )
        }
        if (fieldTypeChildPlans.isNotEmpty()) {
            val env = dataFetchingEnvironmentSupplier.get()
            fieldTypeChildPlans.forEach { childPlan ->
                log.ifDebug {
                    debug("[AccessCheck] Pre-fetching field type child plan for field '${field.fieldName}' of type '$typeName', selection set: '${childPlan.selectionSet}'")
                }
                fieldResolver.launchQueryPlan(
                    typeCheckParameters,
                    childPlan,
                    env,
                    ExecutionParameters.ChildPlanTarget.FieldType(
                        parentResult = fieldResolutionResult.engineResult as ObjectEngineResultImpl,
                        source = fieldResolutionResult.originalSource,
                    ),
                )
            }
        }
        return executeChecker(
            typeCheckParameters,
            dataFetchingEnvironmentSupplier,
            checkerDispatcher,
            objectEngineResult,
            emptyMap(),
            CheckerExecutor.CheckerType.TYPE,
        )
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
        // Exit early if there is definitely no type check
        if (fieldType !is GraphQLCompositeType ||
            (fieldType is GraphQLObjectType && parameters.engineExecutionContext.dispatcherRegistry.getTypeCheckerDispatcher(fieldType.name) == null)
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
        checkerType: CheckerExecutor.CheckerType
    ): Value<out CheckerResult?> {
        val dataFetchingEnvironment = dataFetchingEnvironmentSupplier.get()
        val baseExecutionContext = parameters.engineExecutionContext.copy(
            dataFetchingEnvironment = dataFetchingEnvironment
        )
        val instrumentedDispatcher = parameters.instrumentation.instrumentAccessCheck(
            dispatcher.executor,
            dataFetchingEnvironment,
            InstrumentationExecutionStrategyParameters(parameters.executionContextWithLocalContext, parameters.gjParameters),
            parameters.executionContext.instrumentationState
        )

        val deferred = coroutineInterop.scopedAsync {
            val rssMap = instrumentedDispatcher.requiredSelectionSets
            val rssData = rssMap.mapValues { (key, rss) ->
                rss?.let {
                    val variables = resolveRSSVariables(
                        rss,
                        arguments,
                        objectEngineResult,
                        parameters.queryEngineResult,
                        baseExecutionContext,
                        parameters.executionContext.graphQLContext,
                        parameters.executionContext.locale,
                    )
                    val oerSelections = FieldExecutionHelpers.createOERSelections(
                        rss,
                        variables,
                        baseExecutionContext,
                    )
                    Pair(
                        baseExecutionContext.engineSelectionSetFactory.engineSelectionSet(it.selections, variables.toMap()),
                        oerSelections,
                    )
                }
            }
            val proxyEODMap = rssMap.mapValues { (key, rss) ->
                val selectionData = rssData[key]
                val visibleEngineSelectionSet = selectionData?.first
                val oerSelections = selectionData?.second
                val oerToWrap = if (rss != null && rss.selections.typeName == parameters.graphQLSchema.queryType.name) {
                    parameters.queryEngineResult
                } else {
                    objectEngineResult
                }
                CheckerProxyEngineObjectData(
                    oerToWrap,
                    "missing from checker RSS",
                    visibleEngineSelectionSet,
                    baseExecutionContext.isResolverSelective,
                    oerSelections,
                )
            }
            log.ifDebug {
                val fieldCoord = if (checkerType == CheckerExecutor.CheckerType.FIELD) {
                    "${parameters.executionStepInfo.objectType.name}.${parameters.field!!.fieldName}"
                } else {
                    "${objectEngineResult.type.name}"
                }
                debug("[AccessCheck] Executing ${checkerType.name} access check for '$fieldCoord' at path '${parameters.path}', checker name: '${dispatcher.checkerMetadata?.checkerName}'")
            }
            instrumentedDispatcher.execute(
                arguments,
                proxyEODMap,
                baseExecutionContext,
                checkerType
            )
        }
        return Value.fromDeferred(deferred)
    }
}
