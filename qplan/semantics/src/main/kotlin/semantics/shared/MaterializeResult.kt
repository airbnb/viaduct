package semantics.shared

import model.EngineObjectDataEntry
import model.EngineOutputData
import model.EngineOutputListData
import model.EngineResult
import model.EngineResultCell
import model.EngineResultIsPending
import model.ErrorEngineResult
import model.ListEngineResult
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ObjectMaterializeSelection
import model.PathComponent
import model.materializeCheckedValue
import model.materializeSelectionForestOf
import model.materializedEngineObjectDataOf
import model.outputType
import model.toEngineOutputData
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.FieldDirectives
import viaduct.graphql.schema.ViaductSchema

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
 * [reader] is the exact identity of the resolver or checker consuming the materialized value.
 * [cycleChecker] defaults to no-op for nested `ctx.query` results and correctness replay. Runtime
 * resolver-input materialization supplies its checker explicitly, independently of [operation].
 * When [checked] is true, field- and type-checker promises are awaited, combined, and enforced
 * before a selected value can be consumed; a completed null means no checker applies. A false
 * [checked] value is the narrow raw projection used for
 * checker inputs. It skips checker slots but still cycle-checks and awaits each selected value slot.
 * Rawness is confined to this projection; an active resolver that produces a selected value applies
 * ordinary checked semantics to its own inputs.
 */
internal suspend fun ObjectEngineResult.materializeResult(
    operation: SharedOperationContext<*>,
    selections: MaterializeSelectionForest,
    reader: CycleTask,
    cycleChecker: CycleCheckState = CycleCheckState.createNOP(),
    checked: Boolean = true,
): EngineObjectData.Sync = MaterializationLogic(operation, cycleChecker, checked).materialize(this, selections, reader)

/** Materializes existing result cells for one call using its operation and independently selected cycle checker. */
private class MaterializationLogic(
    private val operation: SharedOperationContext<*>,
    private val cycleChecker: CycleCheckState,
    private val checked: Boolean,
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
            val value =
                cell.materializeValueForConsumer(
                    fieldDirectives = selection.fieldDirectives,
                    reader = reader,
                )
            val selectedValue =
                if (value is ErrorEngineResult) {
                    value.errorData
                } else {
                    value
                        .materializeEngineResultValue(
                            expectedType = storedKey.field.outputType,
                            selections = selection.subselections,
                            reader = reader,
                            resultPath = resultPath + storedKey,
                            fieldDirectives = selection.fieldDirectives,
                        )
                }
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

    private suspend fun ObjectMaterializeSelection.materializedSymbolicKey(): ObjectEngineResult.ObjectKey {
        key.fetchGroundedArguments(operation)
        return key
    }

    // Recursively materializes one selected result while retaining its exact stored path.
    private suspend fun EngineResult?.materializeEngineResultValue(
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        selections: MaterializeSelectionForest,
        reader: CycleTask,
        resultPath: List<PathComponent>,
        fieldDirectives: FieldDirectives?,
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
                    fieldDirectives = fieldDirectives,
                )
            }
            else -> toEngineOutputData(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef)
        }

    // Materializes each list element at a path containing its concrete list index.
    private suspend fun ListEngineResult.materializeValues(
        selections: MaterializeSelectionForest,
        reader: CycleTask,
        resultPath: List<PathComponent>,
        fieldDirectives: FieldDirectives?,
    ): EngineOutputListData {
        val materialized = mutableListOf<EngineOutputData?>()
        indices.forEach { index ->
            val cell = get(index)
            val value =
                cell.materializeValueForConsumer(
                    fieldDirectives = fieldDirectives,
                    reader = reader,
                )
            materialized +=
                if (value is ErrorEngineResult) {
                    value.errorData
                } else {
                    value.materializeEngineResultValue(
                        expectedType = typeExpr,
                        selections = selections,
                        reader = reader,
                        resultPath = resultPath + ListEngineResult.Index.of(index),
                        fieldDirectives = fieldDirectives,
                    )
                }
        }
        return materialized
    }

    private suspend fun EngineResultCell.materializeValueForConsumer(
        fieldDirectives: FieldDirectives?,
        reader: CycleTask,
    ): EngineResult? =
        if (checked) {
            materializeCheckedValueForResolver(
                fieldDirectives = fieldDirectives,
                reader = reader,
                cycleChecker = cycleChecker,
            )
        } else {
            cycleChecker.cycleCheck(reader, valueCycleSlot)
            value.await()
        }
}

/** Awaits and materializes one checked resolver input while preserving cycle-read edges. */
internal suspend fun EngineResultCell.materializeCheckedValueForResolver(
    fieldDirectives: FieldDirectives?,
    reader: CycleTask,
    cycleChecker: CycleCheckState,
): EngineResult? {
    cycleChecker.cycleCheck(reader, fieldCheckerCycleSlot)
    fieldCheckerResult.await()

    suspend fun awaitTypeChecker(value: EngineResult?) {
        if (value is ObjectEngineResult) {
            cycleChecker.cycleCheck(reader, value.typeCheckerCycleSlot)
            value.typeCheckerResult.await()
        }
    }

    cycleChecker.cycleCheck(reader, valueCycleSlot)
    val valuePromise = value
    if (valuePromise.isCompleted) awaitTypeChecker(valuePromise.get())
    var attempt =
        materializeCheckedValue { error ->
            error.isErrorForResolver(CheckerResultContext(fieldDirectives))
        }
    if (attempt === EngineResultIsPending) {
        awaitTypeChecker(valuePromise.await())
        attempt =
            materializeCheckedValue { error ->
                error.isErrorForResolver(CheckerResultContext(fieldDirectives))
            }
    }
    check(attempt !== EngineResultIsPending) {
        "Completed resolver-input slots produced a pending materialization attempt"
    }
    return attempt
}
