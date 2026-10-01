@file:Suppress("MatchingDeclarationName")

package viaduct.engine.runtime2.correctresolution

import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.FieldDirectives
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.findStoredKey
import viaduct.engine.runtime2.resolution.framework.isIncluded

internal class ResolverReplayInputs(
    val objectValue: EngineObjectData.Sync,
    val queryValue: EngineObjectData.Sync,
)

/**
 * Validates observed projections against completed results before using them for relation replay.
 * A field denial may have short-circuited an unfinished object before its type result existed.
 * Completed slots alone cannot recover which allowed denial the consumer saw. Accept only the
 * exact applicable field error at that selection as an alternative to the final combined value;
 * this is value conformance, not a claim about the read's timing.
 */
internal fun SharedOperationContext<*>.resolverInputsForReplay(
    occurrenceId: ResolverOccurrenceId,
    objectResult: ObjectEngineResult?,
    objectSelections: MaterializeSelectionForest,
    querySelections: MaterializeSelectionForest,
    expectedObjectValue: EngineObjectData.Sync,
    expectedQueryValue: EngineObjectData.Sync,
): ResolverReplayInputs? {
    val observer = resolverObserver as? CorrectnessResolverObserver
    if (observer == null || !observer.hasResolverInvocations()) {
        return ResolverReplayInputs(expectedObjectValue, expectedQueryValue)
    }
    val invocations = observer.resolverInvocations(occurrenceId)
    val first = invocations.firstOrNull() ?: return null
    val queryResult = observer.queryFragmentResults(occurrenceId).singleOrNull()
    val validator = ResolverInputConformanceLogic(this)
    if (invocations.any { invocation ->
            !validator.objectInputConforms(objectResult, objectSelections, invocation.input, expectedObjectValue) ||
                !validator.objectInputConforms(queryResult, querySelections, invocation.queryValue, expectedQueryValue) ||
                !invocation.input.sameMaterializedValueAs(first.input) ||
                !invocation.queryValue.sameMaterializedValueAs(first.queryValue)
        }
    ) {
        return null
    }
    return ResolverReplayInputs(first.input, first.queryValue)
}

/** Checks alternative field-denial positions against their exact cells and consumption directives. */
private class ResolverInputConformanceLogic(private val operation: SharedOperationContext<*>) {
    fun objectInputConforms(
        result: ObjectEngineResult?,
        selections: MaterializeSelectionForest,
        actual: EngineObjectData.Sync,
        expected: EngineObjectData.Sync,
    ): Boolean {
        if (actual.sameMaterializedValueAs(expected)) return true
        if (result == null || actual.schemaType != expected.schemaType || actual.schemaType != result.type) return false
        if (actual.getSelections().toSet() != expected.getSelections().toSet()) return false
        var included = materializeSelectionForestOf()
        selections.forEach { if (it.inclusionCondition.isIncluded(operation)) included += materializeSelectionForestOf(it) }
        return included.collect(result.type).byResponseKey().all { (responseKey, selection) ->
            val key = result.findStoredKey(operation, selection.key) ?: return@all false
            cellInputConforms(
                result.getCell(key),
                selection.subselections,
                selection.fieldDirectives,
                actual.outputValue(responseKey),
                expected.outputValue(responseKey),
            )
        }
    }

    private fun cellInputConforms(
        cell: EngineResultCell,
        selections: MaterializeSelectionForest,
        fieldDirectives: FieldDirectives?,
        actual: ResolverOutputData?,
        expected: ResolverOutputData?,
    ): Boolean {
        if (actual.sameMaterializedValueAs(expected)) return true
        val fieldError = cell.fieldCheckerResult.get()?.asError
        if (actual is EngineErrorData && fieldError != null &&
            actual.cause === fieldError.error && fieldError.isErrorForResolver(CheckerResultContext(fieldDirectives))
        ) {
            return true
        }
        val raw = cell.value.get()
        return when {
            raw is ObjectEngineResult && actual is EngineObjectData.Sync && expected is EngineObjectData.Sync ->
                objectInputConforms(raw, selections, actual, expected)
            raw is ListEngineResult && actual is List<*> && expected is List<*> ->
                raw.size == actual.size && raw.size == expected.size && raw.indices.all { index ->
                    cellInputConforms(raw[index], selections, fieldDirectives, actual[index], expected[index])
                }
            else -> false
        }
    }
}

internal fun EngineObjectData.Sync.sameMaterializedValueAs(other: EngineObjectData.Sync): Boolean {
    if (schemaType != other.schemaType) return false
    val selections = getSelections().toSet()
    if (selections != other.getSelections().toSet()) return false
    return selections.all { selection -> outputValue(selection).sameMaterializedValueAs(other.outputValue(selection)) }
}

private fun ResolverOutputData?.sameMaterializedValueAs(other: ResolverOutputData?): Boolean =
    when {
        this is EngineErrorData && other is EngineErrorData -> cause === other.cause || cause == null && other.cause == null
        this is EngineObjectData.Sync && other is EngineObjectData.Sync -> sameMaterializedValueAs(other)
        this is List<*> && other is List<*> -> size == other.size && indices.all { this[it].sameMaterializedValueAs(other[it]) }
        else -> this == other
    }
