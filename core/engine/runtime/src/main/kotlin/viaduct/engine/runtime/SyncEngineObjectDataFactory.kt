package viaduct.engine.runtime

import graphql.execution.ResultPath
import graphql.schema.GraphQLObjectType
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.FieldDirectives
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.ACCESS_CHECK_SLOT
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.RAW_VALUE_SLOT
import viaduct.engine.runtime.result.ObjectEngineResult

/**
 * Factory for creating [SyncProxyEngineObjectData] by eagerly resolving all selections
 * from an [ObjectEngineResult].
 *
 * Eagerly resolves all selections from an [ObjectEngineResult]. Field-level errors are
 * stored as [Exception] instances in the backing map and thrown when the field is accessed.
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
     * @return A [SyncProxyEngineObjectData] with all selections resolved
     */
    suspend fun resolve(
        objectEngineResult: ObjectEngineResult,
        errorMessage: String,
        selectionSet: EngineSelectionSet? = null,
        parentPath: ResultPath? = null,
        skipAccessCheck: Boolean = false,
        instrumentationContext: ResolverInstrumentationContext? = null,
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

        return resolveImpl(objectEngineResult, errorMessage, selectionSet, parentPath, skipAccessCheck, instrumentationContext)
    }

    /**
     * Internal implementation that resolves selections from a non-null selection set.
     * Called from [resolve] for the top-level case and from [unwrap] for nested objects.
     *
     * Cell slot [Value]s are collected non-suspendingly in a first pass, then awaited before
     * [unwrap] assembles the results ([unwrap] does not suspend for the [Cell] case once slots
     * are complete).
     *
     * All slots are awaited together in a single [Value.waitAll], collapsing 2×N serial
     * suspend-resume cycles down to one suspension point per resolver invocation. When
     * instrumentation is enabled, per-selection instrumentation is started before the batched
     * await and finished from callbacks on each selection's slot completion.
     */
    @Suppress("USELESS_IS_CHECK") // defensive check for Cell type
    private suspend fun resolveImpl(
        objectEngineResult: ObjectEngineResultImpl,
        errorMessage: String,
        selectionSet: EngineSelectionSet,
        parentPath: ResultPath? = null,
        skipAccessCheck: Boolean = false,
        instrumentationContext: ResolverInstrumentationContext? = null,
    ): SyncProxyEngineObjectData {
        val data = mutableMapOf<String, Any?>()

        val projectedSelectionSet = selectionSet.selectionSetForType(objectEngineResult.type.name)
        val engineSelections = projectedSelectionSet.selections()
        val conditionallyExcludedResultKeys = projectedSelectionSet.conditionallyExcludedResultKeys()

        // Phase 1: collect per-selection state and the cell slot Values to await (non-suspending).
        data class SelectionState(
            val selectionName: String,
            val selectionPath: ResultPath?,
            val cell: Any?,
            val subselections: EngineSelectionSet?,
            val fieldDirectives: FieldDirectives?,
            val slotValues: List<Value<Any?>>,
        )

        val selectionStates = ArrayList<SelectionState>(engineSelections.size)

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
            val fieldDirectives =
                selectionSet.fieldDirectivesOfSelection(objectEngineResult.type.name, selectionName)

            val cell = objectEngineResult.getCellOptimistically(
                oerKey(
                    selectionSet = selectionSet,
                    parentType = objectEngineResult.type,
                    selectionName = selectionName,
                    fieldName = engineSelection.fieldName,
                )
            )

            val slotValues = if (cell is Cell) {
                buildList {
                    @Suppress("UNCHECKED_CAST")
                    add(cell.getValue(RAW_VALUE_SLOT) as Value<Any?>)
                    if (!skipAccessCheck) {
                        @Suppress("UNCHECKED_CAST")
                        add(cell.getValue(ACCESS_CHECK_SLOT) as Value<Any?>)
                    }
                }
            } else {
                emptyList()
            }
            selectionStates += SelectionState(selectionName, selectionPath, cell, subselections, fieldDirectives, slotValues)
        }

        instrumentationContext?.let { context ->
            for (state in selectionStates) {
                val params = ViaductResolverInstrumentation.InstrumentFetchSelectionParameters(
                    selection = state.selectionName,
                    parentTypeName = objectEngineResult.type.name,
                    resultPath = state.selectionPath,
                )
                val fetchSelectionInstrumentation = context.instrumentation.beginFetchSelection(
                    params,
                    context.state,
                )
                // invokeOnCompletion (not thenApply) so finish() also fires on cancellation;
                // thenApply routes through Deferred.handle, which swallows CancellationException.
                Value.waitAll(state.slotValues).asDeferred().invokeOnCompletion { throwable ->
                    fetchSelectionInstrumentation.finish(throwable)
                }
            }
        }

        // Await all async cell slots concurrently. awaitOrElse rather than await() so that a
        // SyncThrow fast-path in waitAll doesn't escape — errors stay in the slots for unwrap().
        Value.waitAll(selectionStates.flatMap { it.slotValues }).awaitOrElse { }

        // Cell slots are now complete; unwrap() does not suspend for the Cell case.
        for (state in selectionStates) {
            data[state.selectionName] = unwrap(
                state.cell,
                state.subselections,
                errorMessage,
                state.selectionPath,
                skipAccessCheck,
                state.fieldDirectives,
                instrumentationContext,
            )
        }

        return SyncProxyEngineObjectData(
            objectEngineResult.type,
            data,
            errorMessage,
            conditionallyExcludedResultKeys,
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
     * @param fieldDirectives directives from the original field selection, propagated through
     *   nested list items so checker errors can evaluate resolver-read directive context.
     * @return The unwrapped value, or an [Exception] if an error was encountered
     */
    private suspend fun unwrap(
        value: Any?,
        subselections: EngineSelectionSet?,
        errorMessage: String,
        parentPath: ResultPath? = null,
        skipAccessCheck: Boolean = false,
        fieldDirectives: FieldDirectives? = null,
        instrumentationContext: ResolverInstrumentationContext? = null,
    ): Any? {
        return when (value) {
            null -> null

            // Lists (should) always contain `Cell`s, so the recursion here goes
            // to the `Cell` case. If any element has an error, return that error
            // as the value for the whole list.
            is List<*> -> value.mapIndexed { index, it ->
                val v = unwrap(
                    it,
                    subselections,
                    errorMessage,
                    parentPath?.segment(index),
                    skipAccessCheck,
                    fieldDirectives,
                    instrumentationContext,
                )
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
                resolveImpl(value, errorMessage, nestedSelections, parentPath, skipAccessCheck, instrumentationContext)
            }

            is FieldResolutionResult -> {
                if (value.errors.isNotEmpty()) {
                    return FieldErrorsException(value.errors) // Store exception, don't throw
                }
                unwrap(
                    value.engineResult,
                    subselections,
                    errorMessage,
                    parentPath,
                    skipAccessCheck,
                    fieldDirectives,
                    instrumentationContext,
                )
            }

            is Cell -> {
                // Use awaitOrElse rather than fetch() so that a SyncThrow slot does not
                // throw here. The error is folded into cellRaw as the exception object itself, which
                // then falls through to the `else` branch of the recursive unwrap() call and gets
                // stored in the backing map. It will be thrown when the field is accessed.
                @Suppress("UNCHECKED_CAST")
                val cellRaw = (value.getValue(RAW_VALUE_SLOT) as Value<Any?>).awaitOrElse { it }
                // If the raw slot already has an exception, skip the checker — a resolver failure
                // can leave both slots exceptional (e.g. via combineWithTypeCheck), and fetching
                // the checker slot would throw and escape resolveImpl.
                if (cellRaw is Exception) return cellRaw
                if (!skipAccessCheck) {
                    val cellChecker = value.fetch(ACCESS_CHECK_SLOT)
                    val checkerException = extractResolverCheckerException(cellChecker, fieldDirectives)
                    if (checkerException != null) {
                        return checkerException // Store extracted exception, don't throw
                    }
                }
                unwrap(
                    cellRaw,
                    subselections,
                    errorMessage,
                    parentPath,
                    skipAccessCheck,
                    fieldDirectives,
                    instrumentationContext,
                )
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
    ): ObjectEngineResult.Key {
        val engineSelection = selectionSet.resolveSelection(parentType.name, selectionName)
        val args = selectionSet.argumentsOfSelection(engineSelection.typeCondition, engineSelection.selectionName) ?: emptyMap()
        return ObjectEngineResult.Key(
            fieldName,
            engineSelection.selectionName,
            args,
        )
    }
}

class FieldErrorsException(val graphQLErrors: List<graphql.GraphQLError>) : RuntimeException()
