package viaduct.engine.runtime2.model

import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

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
    if (this is RootFieldReferenceDataCarrier) {
        return referenceData.conformsToOutputSchemaType(typeExpr, rootFieldReferencesAllowed)
    }

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
