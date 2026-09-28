package semantics.contract

import model.requireQueryTypeDef
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import viaduct.graphql.schema.ViaductSchema
import model.SelectionForest
import model.fragmentFrom
import semantics.shared.instantiateBindings
import semantics.shared.SharedOperationContext
import model.merge
import model.objectOf
import semantics.arbitrary.Config
import semantics.arbitrary.DuplicateSelectionWeight
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.ListTypeWeight
import semantics.arbitrary.ListValueSize
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.NullValueWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.RecursiveOutputEdgesEnabled
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.resolverTestBatch
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Generated coverage for demand deepening through passive list-valued fields. */
interface ListPassiveDeepeningGeneratedResolverContract : ResolverContract {
    @Test
    fun `list-heavy passive deepening worlds resolve with exact witnessed applications`() {
        val counts = TestCaseCount(schemas = 12, registriesPerSchema = 2, queriesPerSchema = 4)
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.0) +
                (ExplicitFieldResolverWeight to 0.8) +
                (DuplicateSelectionWeight to 0.8) +
                (ListTypeWeight to 1.0) +
                (ListValueSize to 2..3) +
                (NullValueWeight to 0.0) +
                (ErrorValueWeight to 0.0) +
                (RecursiveOutputEdgesEnabled to false) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 1.0) +
                (NodeResolversEnabled to false)
        val random = RandomSource.seeded(817_206L)
        var verifiedCases = 0
        var listDeepeningCases = 0

        repeat(counts.schemas) {
            val batch = Arb.resolverTestBatch(counts, config).next(random)
            batch.registries.forEach { registry ->
                val testWorld = registry.world(batch.schema)
                batch.queries.forEach { query ->
                    val world = testWorld.newAssumptions(selectiveResolvers)
                    val operation =
                        SharedOperationContext.create(world, resolverObserver = registry.resolverObserver())
                    val fragment = world.fragmentFrom(query.source)
                    listDeepeningCases +=
                        countListPassiveDeepening(
                            operation,
                            fragment.subselections,
                            world.schema.requireQueryTypeDef(),
                        )
                    registry.clearResolutionWitness()
                    val result =
                        resolve(
                            operation = operation,
                            root = world.objectOf("Query"),
                            selections = fragment.subselections,
                        )
                    val witness = registry.resolutionWitness()

                    assertEquals(
                        result.registeredResolverApplicationIdentityCounts(operation),
                        witness.applicationIdentityCounts(),
                    )
                    assertTrue(
                        result.correctResolution(operation, fragment),
                    )
                    verifiedCases += 1
                }
            }
        }

        assertEquals(96, verifiedCases)
        assertTrue(listDeepeningCases > 0)
    }
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
            .objectFragment
            .merge(type)
            .instantiateBindings(operation)
            .byGroundKey()
            .forEach requiredField@ { (requiredKey, requiredPassive) ->
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
