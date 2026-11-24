package viaduct.engine.runtime2.contract

import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.instantiateBindings
import viaduct.engine.runtime2.resolvers.instantiateBindings
import viaduct.graphql.schema.ViaductSchema

internal fun listPassiveDeepeningCoverage(): ResolverPropertyCoverage {
    var cases = 0
    return ResolverPropertyCoverage(
        record = { observation ->
            cases += countListPassiveDeepening(observation.ordinary.operation, observation.ordinary.fragment.subselections, observation.ordinary.world.schema.requireQueryTypeDef())
        },
        requireCovered = { run -> run.requireCoverage(cases > 0, "Generated list-passive-deepening profile deepened no passive list demand") },
    )
}

private fun countListPassiveDeepening(
    operation: SharedOperationContext<*>,
    selections: SelectionForest,
    type: ViaductSchema.Object,
): Int {
    val incoming = selections.merge(type).instantiateBindings(operation)
    val incomingByKey = incoming.byGroundKey()
    var count = 0

    incoming.byGroundKey().forEach { (selectedKey, _) ->
        val field = selectedKey.field
        if (field !in operation.world.resolverRegistry) return@forEach

        operation.world.resolverRegistry
            .resolver(field)
            .declaredDependencies.objectFragment
            .merge(type)
            .instantiateBindings(operation)
            .byGroundKey()
            .forEach requiredField@{ (requiredKey, requiredPassive) ->
                val passiveField = requiredKey.field
                val passiveType = passiveField.type.baseTypeDef as? ViaductSchema.CompositeTypeDef
                    ?: return@requiredField
                if (
                    passiveField in operation.world.resolverRegistry ||
                    !passiveField.type.isList
                ) {
                    return@requiredField
                }
                val selectedPassive =
                    incomingByKey[requiredKey] ?: return@requiredField
                if (
                    hasMissingDemand(
                        operation,
                        requiredPassive.subselections,
                        selectedPassive.subselections,
                        passiveType.possibleObjectTypes,
                    )
                ) {
                    count += 1
                }
            }
    }

    incoming.byGroundKey().forEach { (key, selection) ->
        val childType = key.field.type.baseTypeDef as? ViaductSchema.CompositeTypeDef
            ?: return@forEach
        childType.possibleObjectTypes.forEach { possibleType ->
            count += countListPassiveDeepening(operation, selection.subselections, possibleType)
        }
    }
    return count
}

private fun hasMissingDemand(
    operation: SharedOperationContext<*>,
    required: SelectionForest,
    selected: SelectionForest,
    possibleTypes: Set<ViaductSchema.Object>,
): Boolean =
    possibleTypes.any { possibleType ->
        val available = selected.merge(possibleType).instantiateBindings(operation).byGroundKey()
        !required
            .merge(possibleType)
            .instantiateBindings(operation)
            .byGroundKey()
            .all { (requirementKey, requirement) ->
                val match = available[requirementKey]
                if (match == null) {
                    false
                } else {
                    val childType = requirementKey.field.type.baseTypeDef as? ViaductSchema.CompositeTypeDef
                    childType == null ||
                        !hasMissingDemand(
                            operation,
                            requirement.subselections,
                            match.subselections,
                            childType.possibleObjectTypes,
                        )
                }
            }
    }
