package viaduct.engine.runtime2.model.registry

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineObjectDataEntry
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.RootFieldReferenceDataCarrier
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.additionalContextForResolver
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.qplanSchemaTypeOrNull
import viaduct.engine.runtime2.model.schemaType
import viaduct.graphql.schema.ViaductSchema

/**
 * Supplies the selection input of a field resolver by projecting this selection-independent result
 * to the requested [demand].
 *
 * For fixed non-selection inputs, the field's behavior produces this result before [demand] is
 * considered. Projecting that same output for different demands makes the results agree at every
 * coordinate selected by both.
 *
 * Simple, null, and error results are unchanged. List results are projected element-wise. Object
 * projection retains only demanded fields supplied by the returned object. A type-conditioned
 * selection that does not apply to a concrete object is omitted before its key is reconstructed
 * against that object's concrete field. Node references are root-field references and remain
 * symbolic until Resolution invokes `Query.node`.
 *
 * An output object that supplies an argument-bearing field is rejected: such fields are always
 * active and can never be retained as passive output.
 *
 * Every applicable selection in [demand] must be declared on the concrete object type or one of
 * its nominal supertypes. A selection retained below a resolver boundary must contain no
 * [Arguments.Variable] in its key arguments; a resolver selection may retain symbolic arguments
 * because projection stops before materializing its key.
 *
 * @throws IllegalArgumentException when a precondition is not met
 */
internal fun ResolverOutputData?.snipToDemand(
    demand: SelectionForest,
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
): ResolverOutputData? {
    if (this == null || this is EngineErrorData || this is RootFieldReferenceData) return this
    if (this is RootFieldReferenceDataCarrier) return referenceData

    val elementType = expectedType.unwrapList()
    if (elementType != null) {
        return (this as List<*>).map { value -> value.snipToDemand(demand, elementType) }
    }
    return when (val type = expectedType.baseTypeDef) {
        is ViaductSchema.SimpleTypeDef -> {
            require(demand.isEmpty()) {
                "Cannot apply subselections to a simple value $this"
            }
            this
        }
        is ViaductSchema.CompositeTypeDef -> (this as EngineObjectData.Sync).snipToDemand(demand, type)
        else -> error("Unsupported output type: ${type.name}")
    }
}

internal fun EngineObjectData.Sync.snipToDemand(
    demand: SelectionForest,
    declaredType: ViaductSchema.OutputTypeDef? = null,
): EngineObjectData.Sync {
    val schemaType = qplanSchemaTypeOrNull ?: schemaType(
        requireNotNull(declaredType as? ViaductSchema.CompositeTypeDef) { "Object projection requires a declared composite type" },
    )
    val merged = demand.merge(schemaType).byKey()
    val selectedFields = ArrayList<EngineObjectDataEntry>(merged.size)
    for ((key, selection) in merged) {
        val concreteField = key.field
        if (!isPresent(concreteField.name)) continue
        require(concreteField.args.isEmpty()) {
            "Resolver output must not supply argument-bearing field " +
                "${schemaType.name}/${concreteField.name}"
        }
        val arguments = key.arguments
        require(arguments is Arguments.Resolved && arguments.fieldValues.isEmpty()) {
            "Passive object field ${schemaType.name}/${concreteField.name} " +
                "must be argumentless"
        }
        val value = outputValue(concreteField.name)
        val selectedValue = value.snipToDemand(selection.subselections, concreteField.outputType)
        selectedFields += EngineObjectDataEntry.of(concreteField.name, concreteField, selectedValue)
    }
    return engineObjectDataOf(schemaType, selectedFields, additionalContextForResolver = additionalContextForResolver)
}
