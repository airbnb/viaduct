package viaduct.engine.runtime2.contract

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.graphql.schema.ViaductSchema

/**
 * Contract for resolvers whose user-declared object fragments are empty.
 */
interface EmptyObjectFragmentResolverContract :
    ResolverContract,
    CrossKeyRecursiveDemandResolverContract {
    @Test
    fun `resolves an externally typename-only query to an empty root`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = "type Query { value: Int }",
                selectiveResolvers = selectiveResolvers,
            )
        val result = resolveAndValidate(world, "query { __typename }")

        assertTrue(result.keys.isEmpty())
    }

    @Test
    fun `accepts position-distinct passive fields in list output`() {
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val input = observation.input
                require(input.hasExactlyFields())
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      items: [Item!]!
                        @resolver(
                          result: [
                            {selected: 1, extra: 2},
                            {selected: 3}
                          ]
                        )
                    }

                    type Item {
                      selected: Int!
                      extra: Int
                    }
                    """.trimIndent(),
            )
        resolveAndValidate(testWorld, "query { items { selected } }", resolverObserver = invocationObserver)
    }

    @Test
    fun `specializes shared list continuation and concrete argument defaults`() {
        val applications = ConcurrentLinkedQueue<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                val input = observation.input
                require(input.hasExactlyFields())
                if (field.name == "computed") {
                    applications += field.containingDef.name
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      items: [Item!]!
                        @resolver(
                          result: [
                            {__typename: "A"},
                            {__typename: "B"}
                          ]
                        )
                    }

                    interface Item {
                      computed: Int!
                    }

                    type A implements Item {
                      computed(factor: Int = 2): Int!
                        @resolver(result: "sum(${'$'}factor)")
                    }

                    type B implements Item {
                      computed: Int! @resolver(result: 3)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val result = resolveAndValidate(testWorld, "query { items { computed } }", resolverObserver = invocationObserver)
        val items =
            assertIs<ListEngineResult>(
                result.getCell(world.schema.contractKey("Query", "items")).get(),
            )
        val a = assertIs<ObjectEngineResult>(items[0].get())
        val b = assertIs<ObjectEngineResult>(items[1].get())

        assertEquals(
            2,
            a.getCell(
                world.schema.contractKey("A", "computed", mapOf("factor" to 2)),
            ).get(),
        )
        assertEquals(
            3,
            b.getCell(world.schema.contractKey("B", "computed")).get(),
        )
        applications.toList().shouldContainExactlyInAnyOrder("A", "B")
    }

    @Test
    fun `applies a concrete implementation default after interface dispatch`() {
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val input = observation.input
                require(input.hasExactlyFields())
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item!
                        @resolver(result: {__typename: "ConcreteItem"})
                    }

                    interface Item {
                      computed: Int!
                    }

                    type ConcreteItem implements Item {
                      computed(factor: Int = 7): Int!
                        @resolver(result: "sum(${'$'}factor)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val result =
            resolveAndValidate(testWorld, "query { item { computed } }", resolverObserver = invocationObserver)
        val item =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "item")).get(),
            )
        val concreteDefaultKey =
            world.schema.contractKey("ConcreteItem", "computed", mapOf("factor" to 7))

        assertEquals(7, item.getCell(concreteDefaultKey).get())
        assertEquals(
            expectedPassiveResultKeys(item.type, setOf(concreteDefaultKey)),
            item.keys,
        )
    }
}

internal fun ViaductSchema.contractObjectType(typeName: String): ViaductSchema.Object = requireType(typeName) as ViaductSchema.Object

internal fun ViaductSchema.contractKey(
    typeName: String,
    fieldName: String,
    arguments: Map<String, Any?> = emptyMap(),
): ObjectEngineResult.GroundKey = ObjectEngineResult.GroundKey.of(requireObjectField(typeName, fieldName), arguments)
