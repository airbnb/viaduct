package viaduct.engine.runtime2.model.registry

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.objectKey
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.outputValue
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
        is ViaductSchema.CompositeTypeDef -> (this as EngineObjectData.Sync).snipToDemand(demand)
        else -> error("Unsupported output type: ${type.name}")
    }
}

internal fun EngineObjectData.Sync.snipToDemand(demand: SelectionForest): EngineObjectData.Sync {
    val schemaType = this.schemaType
    val selectedFields =
        demand
            .merge(schemaType)
            .filter { selection ->
                val field = selection.objectKey(schemaType).field
                if (!isPresent(field.name)) {
                    false
                } else {
                    require(field.args.isEmpty()) {
                        "Resolver output must not supply argument-bearing field " +
                            "${schemaType.name}/${field.name}"
                    }
                    true
                }
            }
            .byGroundKey()
            .map { (key, selection) ->
                val concreteField = key.field
                val arguments = key.arguments
                require(arguments is Arguments.Resolved && arguments.fieldValues.isEmpty()) {
                    "Passive object field ${schemaType.name}/${concreteField.name} " +
                        "must be argumentless"
                }
                val value = outputValue(concreteField.name)
                val selectedValue = value.snipToDemand(selection.subselections, concreteField.outputType)
                concreteField.name to selectedValue
            }
            .toMap()
    return engineObjectDataOf(schemaType, selectedFields)
}
