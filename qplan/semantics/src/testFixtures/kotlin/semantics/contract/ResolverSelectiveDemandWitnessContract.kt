package semantics.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import model.SelectionForest
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.objectOf
import model.registry.fieldResolverOf
import model.requireField
import model.requireType
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import semantics.shared.instantiateBindings
import viaduct.graphql.schema.ViaductSchema

interface ResolverSelectiveDemandWitnessContract : ResolverContract {
    @Test
    fun `producer witness captures exact successor demand`() {
        var producerDemand: SelectionForest? = null
        val invocationObserver = object : semantics.correctresolution.CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: semantics.shared.ResolverInvocationObservation) {
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
