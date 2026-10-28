@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.propertytest.checkResolverTestSuite
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.instantiateBindings
import viaduct.engine.runtime2.resolvers.instantiateBindings
import viaduct.graphql.schema.ViaductSchema

/** Generated coverage for demand deepening through passive list-valued fields. */
interface ListPassiveDeepeningGeneratedResolverContract : ResolverContract {
    @Test
    fun `list-heavy passive deepening worlds resolve with exact witnessed applications`(): Unit =
        runBlocking {
            var listDeepeningCases = 0

            val run =
                checkResolverTestSuite(ResolverTestSuiteDefinition.LIST_PASSIVE_DEEPENING) { testWorld, testCase ->
                    val registry = testCase.registry
                    val world = testWorld.newAssumptions(selectiveResolvers)
                    val operation =
                        SharedOperationContext.create(world, resolverObserver = registry.resolverObserver())
                    val fragment = testWorld.schemas.fragmentFrom(testCase.query.source)
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
                }

            run.requireCoverage(listDeepeningCases > 0, "Generated list-passive-deepening profile deepened no passive list demand")
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
