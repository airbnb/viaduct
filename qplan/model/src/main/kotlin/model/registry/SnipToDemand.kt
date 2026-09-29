package model.registry

import model.Arguments
import model.EngineErrorData
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.SelectionForest
import model.engineObjectDataOf
import model.merge
import model.objectKey
import model.outputValue
import model.schemaType
import viaduct.engine.api.EngineObjectData
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
 * symbolic until Resolver26 invokes `Query.node`.
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
internal fun ResolverOutputData?.snipToDemand(demand: SelectionForest): ResolverOutputData? =
    when (this) {
        null,
        is EngineErrorData,
        is RootFieldReferenceData,
        -> this

        is EngineObjectData.Sync -> snipObjectToDemand(demand)
        is List<*> -> {
            val values: List<ResolverOutputData?> = map { value -> value.snipToDemand(demand) }
            values
        }

        is Int,
        is Double,
        is String,
        is Boolean,
        -> {
            require(demand.isEmpty()) {
                "Cannot apply subselections to a simple value $this"
            }
            this
        }
        else -> throw ClassCastException("Unsupported engine output data: $this")
    }

private fun EngineObjectData.Sync.snipObjectToDemand(demand: SelectionForest): EngineObjectData.Sync {
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
                val selectedValue =
                    if (concreteField.type.baseTypeDef is ViaductSchema.SimpleTypeDef) {
                        value
                    } else {
                        value.snipToDemand(selection.subselections)
                    }
                concreteField.name to selectedValue
            }
            .toMap()
    return engineObjectDataOf(schemaType, selectedFields)
}
