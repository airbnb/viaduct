package viaduct.engine.runtime

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.api.RawSelection
import viaduct.engine.api.RawSelectionSet
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
     * @param errorMessage The error message template for UnsetSelectionException
     * @param selectionSet The selections to resolve; if null, returns empty data
     * @return A [SyncProxyEngineObjectData] with all selections resolved
     */
    suspend fun resolve(
        objectEngineResult: ObjectEngineResult,
        errorMessage: String,
        selectionSet: RawSelectionSet? = null,
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

        return resolveImpl(objectEngineResult, errorMessage, selectionSet)
    }

    /**
     * Internal implementation that resolves selections from a non-null selection set.
     * Called from [resolve] for the top-level case and from [unwrap] for nested objects.
     */
    private suspend fun resolveImpl(
        objectEngineResult: ObjectEngineResultImpl,
        errorMessage: String,
        selectionSet: RawSelectionSet,
    ): SyncProxyEngineObjectData {
        val data = mutableMapOf<String, Any?>()

        val selections = selectionSet
            .selectionSetForType(objectEngineResult.type.name)
            .selections()

        for (selection in selections) {
            val selectionName = selection.selectionName

            val rawSelection = selectionSet.resolveSelection(
                objectEngineResult.type.name,
                selectionName
            )

            val subselections = maybeSelections(
                objectEngineResult,
                selectionSet,
                rawSelection.fieldName,
                selectionName
            )

            val cell = objectEngineResult.getCellOptimistically(oerKey(selectionSet, rawSelection))
            data[selectionName] = unwrap(cell, subselections, errorMessage)
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
        subselections: RawSelectionSet?,
        errorMessage: String,
    ): Any? {
        return when (value) {
            null -> null

            // Lists (should) always contain `Cell`s, so the recursion here goes
            // to the `Cell` case. If any element has an error, return that error
            // as the value for the whole list (matching ProxyEngineObjectData behavior).
            is List<*> -> value.map {
                val v = unwrap(it, subselections, errorMessage)
                if (v is Exception) return v // non-local return from unwrap
                v
            }

            is ObjectEngineResultImpl -> {
                val exception = value.resolvedExceptionOrNull()
                if (exception != null) return exception // Store exception, don't throw
                // Nested objects always have subselections (they're composite types)
                val nestedSelections = requireNotNull(subselections) {
                    "Expected subselections for nested ObjectEngineResultImpl"
                }
                resolveImpl(value, errorMessage, nestedSelections)
            }

            is FieldResolutionResult -> {
                if (value.errors.isNotEmpty()) {
                    return FieldErrorsException(value.errors) // Store exception, don't throw
                }
                unwrap(value.engineResult, subselections, errorMessage)
            }

            is Cell -> {
                val cellRaw = value.fetch(RAW_VALUE_SLOT)
                val cellChecker = value.fetch(ACCESS_CHECK_SLOT)
                val checkerException = extractCheckerException(cellChecker)
                if (checkerException != null) {
                    return checkerException // Store extracted exception, don't throw
                }
                unwrap(cellRaw, subselections, errorMessage)
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
        selectionSet: RawSelectionSet,
        fieldName: String,
        selectionName: String,
    ): RawSelectionSet? {
        val field = objectEngineResult.type.getField(fieldName)
        return if (GraphQLTypeUtil.unwrapAll(field.type) is GraphQLCompositeType) {
            selectionSet.selectionSetForSelection(
                objectEngineResult.type.name,
                selectionName
            )
        } else {
            null
        }
    }

    private fun oerKey(
        selectionSet: RawSelectionSet,
        rawSelection: RawSelection,
    ): ObjectEngineResult.Key {
        val args = selectionSet.argumentsOfSelection(
            rawSelection.typeCondition,
            rawSelection.selectionName
        ) ?: emptyMap()
        return ObjectEngineResult.Key(
            rawSelection.fieldName,
            rawSelection.selectionName,
            args
        )
    }
}
