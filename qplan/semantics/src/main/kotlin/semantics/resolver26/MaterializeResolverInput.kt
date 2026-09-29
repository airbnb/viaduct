package semantics.resolver26

import model.EngineObjectDataEntry
import model.EngineOutputData
import model.EngineOutputListData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ObjectMaterializeSelection
import model.PathComponent
import model.materializeSelectionForestOf
import model.materializedEngineObjectDataOf
import model.outputType
import model.toEngineOutputData
import semantics.shared.CycleCheckState
import semantics.shared.CycleTask
import semantics.shared.SharedOperationContext
import semantics.shared.fetchGroundedArguments
import semantics.shared.fetchIncluded
import semantics.shared.materializeCheckedValueForResolver
import semantics.shared.valueCycleSlot
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.FieldDirectives
import viaduct.graphql.schema.ViaductSchema

/**
 * Materializes a Resolver26 runtime object or declared Query-fragment input by response key,
 * preserving aliases, inclusion conditions, and null/error/list structure.
 *
 * This implementation can reserve symbolic cells and value promises before their producers
 * install them, then await values and argument bindings without rekeying the cells. [reader]
 * identifies the consuming resolver; [resultPath] identifies the selected object's occurrence.
 * The caller independently supplies [cycleChecker] for the reads. Claimed field- and type-checker
 * slots are awaited, combined, and enforced before the raw value is awaited; unclaimed slots
 * default open, and an applicable denial short-circuits the raw read.
 *
 * A nested `ctx.query` call is distinct from a resolver's declared Query fragment: its result
 * uses [semantics.shared.materializeResult]. Correctness replay also uses that shared API to
 * reconstruct resolver inputs from existing results, including Resolver26's symbolic results.
 */
internal suspend fun ObjectEngineResult.materializeResolverInput(
    operation: SharedOperationContext<*>,
    cycleChecker: CycleCheckState,
    selections: MaterializeSelectionForest,
    reader: CycleTask,
    resultPath: List<PathComponent>,
    checked: Boolean = true,
): EngineObjectData.Sync = ResolverInputMaterializationLogic(operation, cycleChecker, checked).materialize(this, selections, reader, resultPath)

/** Materializes one resolver input, reserving symbolic cells under its operation and selected cycle checker. */
private class ResolverInputMaterializationLogic(
    private val operation: SharedOperationContext<*>,
    private val cycleChecker: CycleCheckState,
    private val checked: Boolean,
) {
    suspend fun materialize(
        result: ObjectEngineResult,
        selections: MaterializeSelectionForest,
        reader: CycleTask,
        resultPath: List<PathComponent>,
    ): EngineObjectData.Sync =
        result.materializeSelectedObject(
            selections = selections,
            reader = reader,
            resultPath = resultPath,
        )

    // Materializes selected OER values at their exact stored paths.
    private suspend fun ObjectEngineResult.materializeSelectedObject(
        selections: MaterializeSelectionForest,
        reader: CycleTask,
        resultPath: List<PathComponent>,
    ): EngineObjectData.Sync {
        val selectedValues =
            linkedMapOf<String, Pair<ViaductSchema.ObjectField, EngineOutputData?>>()
        selections.fetchIncluded().collect(type).byResponseKey().forEach { (responseKey, selection) ->
            val storedKey = selection.materializedObjectKey()
            val cell = reserveCell(storedKey)
            cell.value
            val checkedValue =
                cell.materializeValueForConsumer(
                    fieldDirectives = selection.fieldDirectives,
                    reader = reader,
                )
            val selectedValue: EngineOutputData? =
                if (checkedValue is ErrorEngineResult) {
                    checkedValue.errorData
                } else {
                    checkedValue.materializeSelectedValue(
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

    // Awaits every argument binding but preserves the selection's symbolic OER-cell identity.
    private suspend fun ObjectMaterializeSelection.materializedObjectKey(): ObjectEngineResult.ObjectKey {
        key.fetchGroundedArguments(operation)
        return key
    }

    // Recursively materializes one selected engine result while preserving null, error, and list shape.
    private suspend fun EngineResult?.materializeSelectedValue(
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        selections: MaterializeSelectionForest,
        reader: CycleTask,
        resultPath: List<PathComponent>,
        fieldDirectives: FieldDirectives?,
    ): EngineOutputData? =
        when (this) {
            null -> null
            is ErrorEngineResult -> errorData
            is ObjectEngineResult -> {
                materializeSelectedObject(
                    selections = selections,
                    reader = reader,
                    resultPath = resultPath,
                )
            }
            is ListEngineResult -> {
                require(expectedType.isList)
                val values: EngineOutputListData =
                    indices.map { index ->
                        val cell = get(index)
                        val checkedValue =
                            cell.materializeValueForConsumer(
                                fieldDirectives = fieldDirectives,
                                reader = reader,
                            )
                        if (checkedValue is ErrorEngineResult) {
                            checkedValue.errorData
                        } else {
                            checkedValue.materializeSelectedValue(
                                expectedType = typeExpr,
                                selections = selections,
                                reader = reader,
                                resultPath = resultPath + ListEngineResult.Index.of(index),
                                fieldDirectives = fieldDirectives,
                            )
                        }
                    }
                values
            }
            else -> toEngineOutputData(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef)
        }

    private suspend fun model.EngineResultCell.materializeValueForConsumer(
        fieldDirectives: FieldDirectives?,
        reader: CycleTask,
    ): EngineResult? =
        if (checked) {
            materializeCheckedValueForResolver(fieldDirectives, reader, cycleChecker)
        } else {
            cycleChecker.cycleCheck(reader, valueCycleSlot)
            value.await()
        }
}
