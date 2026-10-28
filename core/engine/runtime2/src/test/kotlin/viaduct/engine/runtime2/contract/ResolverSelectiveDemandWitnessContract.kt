package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.instantiateBindings
import viaduct.engine.runtime2.resolvers.instantiateBindings
import viaduct.graphql.schema.ViaductSchema

interface ResolverSelectiveDemandWitnessContract : ResolverContract {
    @Test
    fun `producer witness captures exact successor demand`() {
        var producerDemand: SelectionForest? = null
        val invocationObserver = object : viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                if (observation.field.name == "item") producerDemand = observation.suppliedDemand
            }
        }
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Item {
                      base: String!
                      computed: String!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val item = schema.loweredSchema.requireField("Query", "item")
                    val computed = schema.loweredSchema.requireField("Item", "computed")
                    mapOf(
                        item to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Item") {
                                    "base" setTo "input"
                                }
                            },
                        computed to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Item { base }",
                                ),
                            ) { input, _ ->
                                val base =
                                    input.selectionValues().getValue(
                                        "base",
                                    ) as String
                                "computed:$base"
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = testWorld.schemas.fragmentFrom("fragment ignored on Query { item { computed } }")
        val itemType = world.schema.requireType("Item") as ViaductSchema.Object

        val resolution =
            observeResolution(
                world,
                world.objectOf("Query"),
                fragment.subselections,
                resolverObserver = invocationObserver,
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
