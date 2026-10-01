package viaduct.engine.runtime2.model.invariants

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineIDResult
import viaduct.engine.runtime2.model.EngineInputData
import viaduct.engine.runtime2.model.EngineInputListData
import viaduct.engine.runtime2.model.EngineInputObjectData
import viaduct.engine.runtime2.model.EngineOutputData
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.canContainPure
import viaduct.engine.runtime2.model.conformsToArgumentDefinition
import viaduct.engine.runtime2.model.inputType
import viaduct.engine.runtime2.model.nodeReferenceIdentityOrNull
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.qplanSchemaTypeOrNull
import viaduct.graphql.schema.ViaductSchema

/**
 * Whether this EOD recursively contains only engine output data.
 *
 * Qplan's factory validates each selection against its canonical schema field before forgetting
 * that field metadata. This relation checks the retained values without reconstructing field
 * identity from response-key strings.
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
        is Double -> result.isFinite()
        is Int,
        is Boolean,
        is String,
        is EngineIDResult,
        -> true
        else -> false
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
            when (expectedType.name) {
                "Int" -> this is Int
                "Float" -> this is Double && isFinite()
                "String" -> this is String
                "Boolean" -> this is Boolean
                "ID" -> this is String
                else -> false
            }
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
): Boolean =
    when (this) {
        null -> typeExpr.isNullable
        is EngineErrorData -> true
        is List<*> ->
            typeExpr.unwrapList()
                ?.let { elementType ->
                    all { value ->
                        value.conformsToOutputSchemaType(
                            elementType,
                            rootFieldReferencesAllowed,
                        )
                    }
                } ?: false
        is RootFieldReferenceData ->
            rootFieldReferencesAllowed &&
                !typeExpr.isList &&
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
        is EngineObjectData.Sync ->
            if (typeExpr.isList) {
                false
            } else {
                (typeExpr.baseTypeDef as? ViaductSchema.CompositeTypeDef)
                    ?.possibleObjectTypes
                    ?.let { possibleTypes ->
                        // Ensure the resolved object's runtime type is one of the expected output
                        // type's possible concrete types.
                        val qplanType = qplanSchemaTypeOrNull
                        if (qplanType != null) {
                            qplanType in possibleTypes
                        } else {
                            possibleTypes.any { possibleType ->
                                possibleType.name == type.name
                            }
                        }
                    } ?: false
            }
        is Int -> typeExpr.hasScalarType("Int")
        is Double ->
            isFinite() &&
                typeExpr.hasScalarType("Float")
        is String ->
            !typeExpr.isList &&
                when (val expected = typeExpr.baseTypeDef) {
                    is ViaductSchema.Scalar -> expected.name == "String" || expected.name == "ID"
                    is ViaductSchema.Enum -> expected.value(this) != null
                    else -> false
                }
        is Boolean -> typeExpr.hasScalarType("Boolean")
        else -> false
    }

private fun EngineOutputData?.conformsToOutputData(): Boolean =
    when (this) {
        null,
        is EngineErrorData,
        is Int,
        is Boolean,
        is String,
        -> true
        is Double -> isFinite()
        is List<*> -> all { value -> value.conformsToOutputData() }
        is EngineObjectData.Sync ->
            getSelections().all { selection -> get(selection).conformsToOutputData() }
        else -> false
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
        is Int -> typeExpr.hasScalarType("Int")
        is Double ->
            isFinite() &&
                typeExpr.hasScalarType("Float")
        is String -> typeExpr.hasScalarType("String")
        is Boolean -> typeExpr.hasScalarType("Boolean")
        is EngineIDResult -> typeExpr.hasScalarType("ID")
        is ViaductSchema.EnumValue ->
            !typeExpr.isList &&
                typeExpr.baseTypeDef == containingDef &&
                containingDef.value(name) == this
        else -> false
    }

private fun ViaductSchema.TypeExpr<*>.hasScalarType(expectedName: String): Boolean =
    !isList &&
        (baseTypeDef as? ViaductSchema.Scalar)?.name == expectedName

private fun EngineResultCell.hasCompletedCheckerResults(): Boolean {
    fieldCheckerResult.get()
    return true
}
