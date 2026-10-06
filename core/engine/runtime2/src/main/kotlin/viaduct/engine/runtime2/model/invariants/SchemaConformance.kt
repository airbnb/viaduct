package viaduct.engine.runtime2.model.invariants

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineInputData
import viaduct.engine.runtime2.model.EngineInputListData
import viaduct.engine.runtime2.model.EngineInputObjectData
import viaduct.engine.runtime2.model.EngineOutputData
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.QPlanEngineObjectData
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.canContainPure
import viaduct.engine.runtime2.model.conformsToArgumentDefinition
import viaduct.engine.runtime2.model.conformsToScalarInput
import viaduct.engine.runtime2.model.conformsToScalarOutput
import viaduct.engine.runtime2.model.inputType
import viaduct.engine.runtime2.model.isNativeScalarResult
import viaduct.engine.runtime2.model.nodeReferenceIdentityOrNull
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.qplanSchemaTypeOrNull
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

private fun EngineInputData.conformsToInputObjectType(expectedType: ViaductSchema.Input): Boolean {
    val fieldValues = asEngineInputObjectDataOrNull() ?: return false
    if (
        expectedType.fields.any { field ->
            !field.type.isNullable &&
                !field.hasDefault &&
                field.name !in fieldValues
        }
    ) {
        return false
    }
    return fieldValues.all { (fieldName, value) ->
        val field = expectedType.field(fieldName) ?: return@all false
        value.conformsToInputSchemaType(field.inputType)
    }
}

internal fun EngineInputData?.conformsToInputSchemaType(typeExpr: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>): Boolean {
    if (this == null) return typeExpr.isNullable

    val elementType = typeExpr.unwrapList()
    if (elementType != null) {
        return asEngineInputListDataOrNull()
            ?.all { value -> value.conformsToInputSchemaType(elementType) }
            ?: false
    }

    return when (val expectedType = typeExpr.baseTypeDef) {
        is ViaductSchema.Scalar ->
            conformsToScalarInput(expectedType.name)
        is ViaductSchema.Enum ->
            this is String &&
                expectedType.value(this) != null
        is ViaductSchema.Input ->
            conformsToInputObjectType(expectedType)
        else -> false
    }
}

private fun EngineInputData.asEngineInputListDataOrNull(): EngineInputListData? {
    val values = this as? List<*> ?: return null
    return values
}

private fun EngineInputData.asEngineInputObjectDataOrNull(): EngineInputObjectData? {
    val fields = this as? Map<*, *> ?: return null
    if (fields.keys.any { key -> key !is String }) return null
    @Suppress("UNCHECKED_CAST")
    return fields as EngineInputObjectData
}

/** Whether ordinary engine output recursively conforms to [typeExpr]. */
fun EngineOutputData?.conformsToOutputSchemaType(typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>): Boolean = conformsToOutputSchemaType(typeExpr, rootFieldReferencesAllowed = false)

/** Whether resolver output, including symbolic root-field references, conforms to [typeExpr]. */
fun ResolverOutputData?.conformsToResolverOutputSchemaType(typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>): Boolean =
    conformsToOutputSchemaType(typeExpr, rootFieldReferencesAllowed = true)

private fun Any?.conformsToOutputSchemaType(
    typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    rootFieldReferencesAllowed: Boolean,
): Boolean {
    if (this == null) return typeExpr.isNullable
    if (this is EngineErrorData) return true

    val elementType = typeExpr.unwrapList()
    if (elementType != null) {
        return this is List<*> &&
            all { value ->
                value.conformsToOutputSchemaType(elementType, rootFieldReferencesAllowed)
            }
    }
    if (this is RootFieldReferenceData) {
        return rootFieldReferencesAllowed &&
            when (val expectedType = typeExpr.baseTypeDef) {
                is ViaductSchema.CompositeTypeDef ->
                    nodeReferenceIdentityOrNull()?.let { identity ->
                        identity.type in expectedType.possibleObjectTypes
                    } ?: (
                        type is ViaductSchema.CompositeTypeDef &&
                            type.possibleObjectTypes.all(
                                expectedType.possibleObjectTypes::contains,
                            )
                    )
                is ViaductSchema.SimpleTypeDef -> type == expectedType
                else -> false
            }
    }
    return when (val expectedType = typeExpr.baseTypeDef) {
        is ViaductSchema.Scalar -> conformsToScalarOutput(expectedType.name)
        is ViaductSchema.Enum -> this is String && expectedType.value(this) != null
        is ViaductSchema.CompositeTypeDef -> {
            if (this !is EngineObjectData.Sync) return false
            // Validate the concrete runtime type against the declared output type.
            val qplanType = qplanSchemaTypeOrNull
            if (qplanType != null) {
                qplanType in expectedType.possibleObjectTypes
            } else {
                expectedType.possibleObjectTypes.any { possibleType ->
                    possibleType.name == type.name
                }
            }
        }
        else -> false
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

internal fun EngineResult?.conformsToResultSchemaType(typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>): Boolean =
    when (this) {
        null -> typeExpr.isNullable
        is ErrorEngineResult -> true
        is ObjectEngineResult ->
            if (!typeExpr.isList) {
                val declaredType = typeExpr.baseTypeDef
                declaredType is ViaductSchema.CompositeTypeDef && type in declaredType.possibleObjectTypes
            } else {
                false
            }
        is ListEngineResult ->
            typeExpr.unwrapList()?.canContainPure(this.typeExpr) == true
        is ViaductSchema.EnumValue ->
            !typeExpr.isList &&
                typeExpr.baseTypeDef == containingDef &&
                containingDef.value(name) == this
        else ->
            !typeExpr.isList &&
                (typeExpr.baseTypeDef as? ViaductSchema.Scalar)?.let { scalar ->
                    scalarResultTypeNameOrNull() == scalar.name
                } == true
    }

private fun EngineResultCell.hasCompletedCheckerResults(): Boolean {
    fieldCheckerResult.get()
    return true
}
