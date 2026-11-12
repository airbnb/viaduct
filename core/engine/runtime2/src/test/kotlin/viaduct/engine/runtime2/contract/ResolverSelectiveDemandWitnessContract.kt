package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.instantiateBindings
import viaduct.engine.runtime2.resolvers.instantiateBindings
import viaduct.graphql.schema.ViaductSchema

interface ResolverSelectiveDemandWitnessContract : ResolverContract {
    @Test
    fun `producer witness captures exact successor demand`() {
        var producerDemand: SelectionForest? = null
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                if (observation.field.name == "item") producerDemand = observation.suppliedDemand
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query { item: Item! @resolver(script: "{'base': 7}") }
                    type Item {
                      base: Int!
                      computed: Int! @resolver(of: "base", script: "1 + (objectValue.base == null ? 0 : objectValue.base)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val fragment = testWorld.schemas.fragmentFrom("fragment ignored on Query { item { computed } }")
        val itemType = world.schema.requireType("Item") as ViaductSchema.Object

        val resolution =
            observeResolution(
                world,
                world.objectOf("Query"),
                fragment.subselections,
                resolutionObserver = invocationObserver,
            )
        val result = resolution.result

        assertEquals(
            setOf("base", "computed"),
            requireNotNull(producerDemand)
                .merge(itemType)
                .instantiateBindings(resolution.operation)
                .groundKeys()
                .mapTo(linkedSetOf()) { key -> key.field.name },
        )
        assertTrue(
            result.correctResolution(resolution.operation, fragment),
        )
    }
}
