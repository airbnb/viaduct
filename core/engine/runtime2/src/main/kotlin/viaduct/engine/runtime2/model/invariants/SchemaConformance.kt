package viaduct.engine.runtime2.model.invariants

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineInputData
import viaduct.engine.runtime2.model.EngineOutputData
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.QPlanEngineObjectData
import viaduct.engine.runtime2.model.conformsToArgumentDefinition
import viaduct.engine.runtime2.model.conformsToInputSchemaType
import viaduct.engine.runtime2.model.conformsToOutputSchemaType
import viaduct.engine.runtime2.model.conformsToResultSchemaType
import viaduct.engine.runtime2.model.isNativeScalarResult
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.scalarResultTypeNameOrNull
import viaduct.graphql.schema.ViaductSchema

/**
 * Whether this EOD recursively contains only engine output data.
 *
 * Qplan's factory retains each selection's canonical schema field. This relation uses that
 * metadata to validate opaque scalar values without inferring field identity from response keys.
 */
internal fun EngineObjectData.Sync.conformsToSchema(): Boolean = this.conformsToOutputData()

/**
 * Whether this input value recursively conforms to [typeExpr].
 *
 * Null conforms exactly at a nullable outer layer.
 */
internal fun EngineInputData?.conformsToSchema(typeExpr: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>): Boolean = conformsToInputSchemaType(typeExpr)

/**
 * Whether this output value recursively conforms to [typeExpr].
 *
 * Null conforms exactly at a nullable outer layer and [EngineErrorData] conforms to every output
 * type expression.
 */
internal fun EngineOutputData?.conformsToOutputSchema(typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>): Boolean =
    conformsToOutputSchemaType(typeExpr) &&
        (this !is EngineObjectData.Sync || conformsToOutputData())

/** Whether this argument tuple recursively conforms to [expectedType]. */
internal fun Arguments.Resolved.conformsToSchema(expectedField: ViaductSchema.Field): Boolean = conformsToArgumentDefinition(expectedField)

/** Whether this key's arguments recursively conform to its output field. */
internal fun ObjectEngineResult.Key.conformsToSchema(): Boolean {
    val keyArguments = arguments
    return keyArguments.conformsToArgumentDefinition(field)
}

/**
 * Whether this engine result recursively conforms to the schema definitions carried by its
 * coordinates.
 *
 * Parent backedges are validated against [parentFieldRelations].
 *
 * This relation is universally true of engine results constructed by their model factories.
 */
internal fun EngineResult.conformsToSchema(parentFieldRelations: Map<ViaductSchema.ObjectField, ViaductSchema.ObjectField>): Boolean =
    this.conformsToSchema(
        parentFieldRelations = parentFieldRelations,
        ancestors = emptyList(),
    )

private data class StructuralAncestor(
    val result: ObjectEngineResult,
    val producerField: ViaductSchema.ObjectField,
)

private fun EngineResult.conformsToSchema(
    parentFieldRelations: Map<ViaductSchema.ObjectField, ViaductSchema.ObjectField>,
    ancestors: List<StructuralAncestor>,
): Boolean {
    val result = this
    return when (result) {
        is ErrorEngineResult -> true
        is ObjectEngineResult ->
            result.keys.all { key ->
                val cell = result.getCell(key)
                if (
                    key.field.containingDef != result.type ||
                    !key.conformsToSchema()
                ) {
                    return@all false
                }
                if (!cell.value.isCompleted) return@all true
                val value = cell.value.get()
                value.conformsToResultSchemaType(key.field.outputType) &&
                    if (key is ObjectEngineResult.ParentKey) {
                        parentFieldRelations[key.field]?.let { producerField ->
                            val ancestor = ancestors.lastOrNull()
                            ancestor != null &&
                                value === ancestor.result &&
                                producerField == ancestor.producerField
                        } == true
                    } else {
                        value?.conformsToSchema(
                            parentFieldRelations,
                            ancestors + StructuralAncestor(result, key.field),
                        ) ?: true
                    } &&
                    cell.hasCompletedCheckerResults()
            }
        is ListEngineResult ->
            result.all { cell ->
                val value = cell.value.get()
                value.conformsToResultSchemaType(result.typeExpr) &&
                    (value?.conformsToSchema(parentFieldRelations, ancestors) ?: true) &&
                    cell.hasCompletedCheckerResults()
            }
        is ViaductSchema.EnumValue ->
            result.containingDef.value(result.name) == result
        else -> result.scalarResultTypeNameOrNull() != null
    }
}

private fun EngineOutputData?.conformsToOutputData(): Boolean =
    when (this) {
        null,
        is EngineErrorData,
        -> true
        is List<*> -> all { value -> value.conformsToOutputData() }
        is QPlanEngineObjectData ->
            selectionFields.all { (selection, field) ->
                outputValue(selection).conformsToOutputSchema(field.outputType)
            }
        is EngineObjectData.Sync ->
            getSelections().all { selection -> get(selection).conformsToOutputData() }
        else -> isNativeScalarResult()
    }

private fun EngineResultCell.hasCompletedCheckerResults(): Boolean {
    fieldCheckerResult.get()
    return true
}
