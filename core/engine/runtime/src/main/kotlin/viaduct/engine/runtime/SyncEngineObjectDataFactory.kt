package viaduct.engine.runtime

import graphql.execution.ResultPath
import graphql.schema.GraphQLObjectType
import kotlin.coroutines.coroutineContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.instrumentation.resolver.FetchFunction
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.ACCESS_CHECK_SLOT
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.RAW_VALUE_SLOT

/**
 * Factory for creating [SyncProxyEngineObjectData] by eagerly resolving all selections
 * from an [ObjectEngineResult].
 *
 * This is the synchronous counterpart to [ProxyEngineObjectData], which resolves lazily.
 * Use this when you need all selections resolved upfront before accessing any data.
 *
 * Unlike throwing errors immediately during resolution, this factory stores errors
 * as [Exception] instances in the backing map. The exceptions are then thrown when
 * the field is accessed, matching [ProxyEngineObjectData]'s lazy error behavior.
 */
object SyncEngineObjectDataFactory {
    /**
     * Creates a [SyncProxyEngineObjectData] by eagerly resolving all selections
     * in the provided [selectionSet] from the [objectEngineResult].
     *
     * Field-level errors (access check failures, field resolution errors, etc.) are
     * stored in the result and thrown when the field is accessed, rather than during
     * this resolution phase.
     *
     * @param objectEngineResult The engine result containing the raw data
     * @param errorMessage The error message template for UnsetFieldException
     * @param selectionSet The caller-visible selections to resolve; if null, returns empty data.
     * @param parentPath Optional result path used for instrumentation/error attribution.
     * @param isResolverSelective Indicates whether a field's OER key should include its subselections.
     * @return A [SyncProxyEngineObjectData] with all selections resolved
     */
    suspend fun resolve(
        objectEngineResult: ObjectEngineResult,
        errorMessage: String,
        selectionSet: EngineSelectionSet? = null,
        parentPath: ResultPath? = null,
        isResolverSelective: IsResolverSelective,
        selections: ObjectEngineResult.Selections? = null,
    ): SyncProxyEngineObjectData {
        if (selectionSet == null) {
            return SyncProxyEngineObjectData(
                objectEngineResult.type,
                emptyMap(),
                errorMessage
            )
        }

        check(objectEngineResult is ObjectEngineResultImpl) {
            "Expected ObjectEngineResultImpl, got ${objectEngineResult::class.qualifiedName}"
        }

        return resolveImpl(objectEngineResult, errorMessage, selectionSet, parentPath, isResolverSelective, selections)
    }

    /**
     * Internal implementation that resolves selections from a non-null selection set.
     * Called from [resolve] for the top-level case and from [unwrap] for nested objects.
     *
     * If a [ResolverInstrumentationContext] is present in the coroutine context, each selection
     * is wrapped with [ViaductResolverInstrumentation.instrumentFetchSelection] for observability.
     * This context is set by [viaduct.engine.runtime.instrumentation.resolver.InstrumentedFieldResolverDispatcher]
     * when invoking sync value getters. Without it, selections resolve without instrumentation.
     *
     * Batched awaitAll: cell slot [Value]s are collected non-suspendingly in a first pass, then
     * awaited together in a single [Value.waitAll] call. This collapses 2×N serial suspend-resume
     * cycles (one per slot per cell) down to a single suspension point per resolver invocation.
     * After [Value.waitAll] completes, the cell slots are synchronously available and
     * [unwrap] does not suspend for the [Cell] case.
     */
    @Suppress("USELESS_IS_CHECK") // defensive check for Cell type
    private suspend fun resolveImpl(
        objectEngineResult: ObjectEngineResultImpl,
        errorMessage: String,
        selectionSet: EngineSelectionSet,
        parentPath: ResultPath? = null,
        isResolverSelective: IsResolverSelective,
        selections: ObjectEngineResult.Selections? = null,
    ): SyncProxyEngineObjectData {
        val data = mutableMapOf<String, Any?>()
        val instrumentationCtx = coroutineContext[ResolverInstrumentationContext]

        val engineSelections = selectionSet
            .selectionSetForType(objectEngineResult.type.name)
            .selections()

        // Phase 1: collect per-selection state and pre-fetch cell slot Values (non-suspending).
        data class SelectionState(
            val selectionName: String,
            val selectionPath: ResultPath?,
            val cell: Any?,
            val subselections: EngineSelectionSet?,
        )

        val selectionStates = ArrayList<SelectionState>(engineSelections.size)
        val cellValues = mutableListOf<Value<Any?>>()

        for (selection in engineSelections) {
            val selectionName = selection.selectionName
            val selectionPath = parentPath?.segment(selectionName)

            val engineSelection = selectionSet.resolveSelection(objectEngineResult.type.name, selectionName)

            val subselections = maybeSelections(
                objectEngineResult,
                selectionSet,
                engineSelection.fieldName,
                selectionName
            )

            val cell = objectEngineResult.getCellOptimistically(
                oerKey(
                    selectionSet = selectionSet,
                    parentType = objectEngineResult.type,
                    selectionName = selectionName,
                    fieldName = engineSelection.fieldName,
                    isResolverSelective = isResolverSelective,
                    selections = selections,
                )
            )
            selectionStates += SelectionState(selectionName, selectionPath, cell, subselections)

            if (cell is Cell) {
                @Suppress("UNCHECKED_CAST")
                cellValues += cell.getValue(RAW_VALUE_SLOT) as Value<Any?>
                @Suppress("UNCHECKED_CAST")
                cellValues += cell.getValue(ACCESS_CHECK_SLOT) as Value<Any?>
            }
        }

        // Single suspension point: await all incomplete cell slot values concurrently.
        Value.waitAll(cellValues).await()

        // Phase 2: assemble results. Cell slots are now complete; unwrap() does not suspend
        // for the Cell case.
        for (state in selectionStates) {
            val fieldChildSelections = selections?.selectionSetForSelection(objectEngineResult.type, state.selectionName)
            data[state.selectionName] = if (instrumentationCtx != null) {
                val params = ViaductResolverInstrumentation.InstrumentFetchSelectionParameters(
                    selection = state.selectionName,
                    parentTypeName = objectEngineResult.type.name,
                    resultPath = state.selectionPath
                )
                instrumentationCtx.instrumentation.instrumentFetchSelection(
                    FetchFunction {
                        unwrap(
                            state.cell,
                            state.subselections,
                            errorMessage,
                            state.selectionPath,
                            isResolverSelective,
                            fieldChildSelections
                        )
                    },
                    params,
                    instrumentationCtx.state
                ).fetch()
            } else {
                unwrap(
                    state.cell,
                    state.subselections,
                    errorMessage,
                    state.selectionPath,
                    isResolverSelective,
                    fieldChildSelections
                )
            }
        }

        return SyncProxyEngineObjectData(
            objectEngineResult.type,
            data,
            errorMessage
        )
    }

    /**
     * Recursively unwraps a value, converting engine types to their resolved forms.
     * Errors are returned as [Exception] instances rather than thrown, so they can
     * be stored in the backing map and thrown when the field is accessed.
     *
     * Handles:
     * - null/Scalars/Enum: Returns as-is
     * - [List]: Maps over elements recursively
     * - [ObjectEngineResultImpl]: Awaits lazy resolution, then recursively resolves
     * - [FieldResolutionResult]: Unwraps and recurses on engineResult
     * - [Cell]: Extracts raw value and access check, then recurses
     *
     * @return The unwrapped value, or an [Exception] if an error was encountered
     */
    private suspend fun unwrap(
        value: Any?,
        subselections: EngineSelectionSet?,
        errorMessage: String,
        parentPath: ResultPath? = null,
        isResolverSelective: IsResolverSelective,
        childSelections: ObjectEngineResult.Selections? = null,
    ): Any? {
        return when (value) {
            null -> null

            // Lists (should) always contain `Cell`s, so the recursion here goes
            // to the `Cell` case. If any element has an error, return that error
            // as the value for the whole list (matching ProxyEngineObjectData behavior).
            is List<*> -> value.mapIndexed { index, it ->
                val v = unwrap(it, subselections, errorMessage, parentPath?.segment(index), isResolverSelective, childSelections)
                if (v is Exception) return v // non-local return from unwrap
                v
            }

            is ObjectEngineResultImpl -> {
                val exception = value.resolvedExceptionOrNull()
                if (exception != null) return exception // Store exception, don't throw
                if (value.isResolvedToNull()) return null
                // Nested objects always have subselections (they're composite types)
                val nestedSelections = requireNotNull(subselections) {
                    "Expected subselections for nested ObjectEngineResultImpl"
                }
                resolveImpl(value, errorMessage, nestedSelections, parentPath, isResolverSelective, childSelections)
            }

            is FieldResolutionResult -> {
                if (value.errors.isNotEmpty()) {
                    return FieldErrorsException(value.errors) // Store exception, don't throw
                }
                unwrap(value.engineResult, subselections, errorMessage, parentPath, isResolverSelective, childSelections)
            }

            is Cell -> {
                val cellRaw = value.fetch(RAW_VALUE_SLOT)
                val cellChecker = value.fetch(ACCESS_CHECK_SLOT)
                val checkerException = extractCheckerException(cellChecker)
                if (checkerException != null) {
                    return checkerException // Store extracted exception, don't throw
                }
                unwrap(cellRaw, subselections, errorMessage, parentPath, isResolverSelective, childSelections)
            }

            // The `else` case is for non-null simple types (scalars
            // and enums) the implementation here is a bit dangerously
            // broad but attempting to get more surgical here would
            // be expensive.
            else -> value
        }
        // To understand why the above is correct:
        //
        // During query execution, field resolvers run and their results are wrapped
        // in FieldResolutionResult before being stored in the RAW_VALUE_SLOT of a Cell.
        // The FieldResolutionResult contains:
        //    - engineResult - the actual value (which could be an ObjectEngineResultImpl
        //      for nested objects)
        //    - errors - any errors from resolution
        //
        // So unwrap handles: Cell -> FieldResolutionResult -> ObjectEngineResultImpl (for nested objects).
        // List elements are also wrapped in Cells.
    }

    /**
     * Extracts the exception from a [CheckerResult] if it represents an error that
     * should be thrown for resolvers.
     *
     * @param checkerResult The checker result to examine
     * @return The exception to store, or null if no error
     */
    private fun extractCheckerException(checkerResult: Any?): Exception? {
        checkerResult ?: return null
        if (checkerResult !is CheckerResult) {
            return IllegalStateException(
                "Expected access check slot to contain a CheckerResult, got $checkerResult"
            )
        }
        return checkerResult.asError?.let { error ->
            if (error.isErrorForResolver(CheckerResultContext())) {
                error.error
            } else {
                null
            }
        }
    }

    private fun maybeSelections(
        objectEngineResult: ObjectEngineResultImpl,
        selectionSet: EngineSelectionSet,
        fieldName: String,
        selectionName: String,
    ): EngineSelectionSet? = EngineObjectDataUtils.maybeSubselections(objectEngineResult.type, fieldName, selectionName, selectionSet)

    private fun oerKey(
        selectionSet: EngineSelectionSet,
        parentType: GraphQLObjectType,
        selectionName: String,
        fieldName: String,
        isResolverSelective: IsResolverSelective,
        selections: ObjectEngineResult.Selections?,
    ): ObjectEngineResult.Key {
        val engineSelection = selectionSet.resolveSelection(parentType.name, selectionName)
        val args = selectionSet.argumentsOfSelection(engineSelection.typeCondition, engineSelection.selectionName) ?: emptyMap()
        val hasSelectiveResolver =
            isResolverSelective(engineSelection.typeCondition to engineSelection.fieldName) ||
                isResolverSelective(parentType.name to engineSelection.fieldName)
        val oerKeySelections = if (hasSelectiveResolver) {
            selections?.selectionSetForSelection(parentType, selectionName)
        } else {
            null
        }
        return ObjectEngineResult.Key(
            fieldName,
            engineSelection.selectionName,
            args,
            oerKeySelections
        )
    }
}
