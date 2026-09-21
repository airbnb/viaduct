package semantics.shared

import viaduct.graphql.schema.ViaductSchema

import model.EngineOutputData
import model.EngineOutputListData
import model.EngineObjectDataEntry
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.MaterializeSelectionForest
import model.materializeSelectionForestOf
import model.ObjectEngineResult
import model.outputType
import model.ObjectMaterializeSelection
import model.PathComponent
import model.materializedEngineObjectDataOf
import model.toEngineOutputData
import viaduct.engine.api.EngineObjectData

/**
 * Projects the OER values selected by [selections] into response-keyed [EngineObjectData], preserving
 * aliases, inclusion conditions, and null/error/list structure.
 *
 * Selected cells and value promises must be installed. Their values need not be finished: this
 * call can await unfinished values and selection argument or inclusion-condition bindings.
 * Selection variables must already have their occurrence identities.
 *
 * Supports ground keys and contextually grounded symbolic keys. Lookup prefers the exact symbolic
 * key and falls back to a grounded stored key; materialization never rekeys cells.
 *
 * Used by Resolver01-23's input-materialization wrapper, nested `ctx.query` execution, and
 * correctness replay across all resolver families. Replay reconstructs resolver inputs from
 * existing results to re-evaluate deterministic resolver relations; those results can retain
 * symbolic key identities even after their bindings are resolved.
 *
 * [reader] is the exact identity of the resolver consuming the materialized value.
 * [cycleChecker] defaults to no-op for nested `ctx.query` results and correctness replay. Runtime
 * resolver-input materialization supplies its checker explicitly, independently of [operation].
 */
internal suspend fun ObjectEngineResult.materializeResult(
    operation: SharedOperationContext<*>,
    selections: MaterializeSelectionForest,
    reader: CycleTask,
    cycleChecker: CycleCheckState = CycleCheckState.createNOP(),
): EngineObjectData.Sync =
    MaterializationLogic(operation, cycleChecker).materialize(this, selections, reader)

/** Materializes existing result cells for one call using its operation and independently selected cycle checker. */
private class MaterializationLogic(
    private val operation: SharedOperationContext<*>,
    private val cycleChecker: CycleCheckState,
) {
    suspend fun materialize(
        result: ObjectEngineResult,
        selections: MaterializeSelectionForest,
        reader: CycleTask,
    ): EngineObjectData.Sync =
        result.materializeSelectedObjectValue(
            selections = selections,
            reader = reader,
            resultPath = reader.path.dropLast(1),
        )

    // Materializes a selection forest rooted at one exact OER path.
    private suspend fun ObjectEngineResult.materializeSelectedObjectValue(
        selections: MaterializeSelectionForest,
        reader: CycleTask,
        resultPath: List<PathComponent>,
    ): EngineObjectData.Sync {
        val selectedValues =
            linkedMapOf<String, Pair<ViaductSchema.ObjectField, EngineOutputData?>>()
        selections.fetchIncluded().collect(type).byResponseKey().forEach { (responseKey, selection) ->
            val candidateKey = selection.materializedSymbolicKey()
            val storedKey = findStoredKey(operation, candidateKey) ?: candidateKey
            val cell = getCell(storedKey)
            val promise = cell.getValue()
            cycleChecker.cycleCheck(reader, cell.valueCycleSlot)
            val selectedValue =
                promise
                    .await()
                    .materializeEngineResultValue(
                        expectedType = storedKey.field.outputType,
                        selections = selection.subselections,
                        reader = reader,
                        resultPath = resultPath + storedKey,
                    )
            selectedValues[responseKey] = storedKey.field to selectedValue
        }
        return materializedEngineObjectDataOf(
            schemaType = type,
            fields =
                selectedValues.map { (key, fieldAndValue) ->
                    EngineObjectDataEntry.of(key, fieldAndValue.first, fieldAndValue.second)
                },
        )
    }

    private suspend fun MaterializeSelectionForest.fetchIncluded(): MaterializeSelectionForest {
        var included = materializeSelectionForestOf()
        val selections = mutableListOf<model.MaterializeSelection>()
        forEach(selections::add)
        for (selection in selections) {
            if (selection.inclusionCondition.fetchIncluded(operation)) {
                included += materializeSelectionForestOf(selection)
            }
        }
        return included
    }

    private suspend fun ObjectMaterializeSelection.materializedSymbolicKey(
    ): ObjectEngineResult.ObjectKey {
        key.fetchGroundedArguments(operation)
        return key
    }

    // Recursively materializes one selected result while retaining its exact stored path.
    private suspend fun EngineResult?.materializeEngineResultValue(
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        selections: MaterializeSelectionForest,
        reader: CycleTask,
        resultPath: List<PathComponent>,
    ): EngineOutputData? =
        when (this) {
            null -> null
            is ErrorEngineResult -> errorData
            is ObjectEngineResult ->
                materializeSelectedObjectValue(
                    selections = selections,
                    reader = reader,
                    resultPath = resultPath,
                )
            is ListEngineResult -> {
                require(expectedType.isList)
                materializeValues(
                    selections = selections,
                    reader = reader,
                    resultPath = resultPath,
                )
            }
            else -> toEngineOutputData(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef)
        }

    // Materializes each list element at a path containing its concrete list index.
    private suspend fun ListEngineResult.materializeValues(
        selections: MaterializeSelectionForest,
        reader: CycleTask,
        resultPath: List<PathComponent>,
    ): EngineOutputListData {
        val materialized = mutableListOf<EngineOutputData?>()
        indices.forEach { index ->
            val cell = get(index)
            cycleChecker.cycleCheck(reader, cell.valueCycleSlot)
            materialized +=
                cell.getValue().await().materializeEngineResultValue(
                    expectedType = typeExpr,
                    selections = selections,
                    reader = reader,
                    resultPath = resultPath + ListEngineResult.Index.of(index),
                )
        }
        return materialized
    }
}
