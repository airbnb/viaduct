package viaduct.engine.runtime2.resolution

import io.kotest.matchers.shouldBe
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.graphql.schema.ViaductSchema

class RegistrySuccessorDemandCacheTest {
    @Test
    fun `fixed expansions are reused across fresh assumptions without retaining the incoming guard`() {
        val fixture = acyclicFixture()
        val registry = fixture.resolverRegistry
        val cache = (registry as FixedSuccessorDemandCacheOwner).fixedSuccessorDemandCache
        val boundary = SuccessorBoundary(field = fixture.schema.requireObjectField("Query", "first"), kind = SuccessorBoundaryKind.RESOLVER)
        val input = fixture.schemas.fragmentFrom("fragment F on Query { first }").subselections
        val expected = input.successorDemand(uncachedWorld(fixture.assumptions)).shape()
        assertNull(cache.get(boundary))

        input.successorDemand(fixture.newAssumptions()).shape() shouldBe expected
        val expansion = cache.get(boundary)
        assertNotNull(expansion)
        val excluded = fixture.schemas.fragmentFrom("fragment F on Query { first @skip(if: true) }").subselections
        excluded.successorDemand(fixture.newAssumptions()).shape() shouldBe excluded.successorDemand(uncachedWorld(fixture.assumptions)).shape()
        input.successorDemand(fixture.newAssumptions()).shape() shouldBe expected
        assertSame(expansion, cache.get(boundary))
    }

    @Test
    fun `decorating a warmed registry does not reuse answers for an overridden checker`() {
        val fixture = acyclicFixture()
        val world = fixture.assumptions
        val input = fixture.schemas.fragmentFrom("fragment F on Query { first }").subselections
        input.successorDemand(world)
        val first = world.schema.requireObjectField("Query", "first")
        val checker =
            FieldCheckerResolver.of(
                first,
                world.schema.requireQueryTypeDef(),
                mapOf(
                    "raw" to ResolverFragmentTemplates(
                        fixture.schemas.fragmentFrom("fragment F on Query { checkerOnly }").materializeSelections,
                        materializeSelectionForestOf(),
                    ),
                ),
            ) { _, _, _ -> CheckerResult.Success }
        val decorated: ResolverRegistry = object : ResolverRegistry by world.resolverRegistry {
            override fun fieldChecker(field: ViaductSchema.ObjectField) = if (field == first) checker else world.resolverRegistry.fieldChecker(field)
        }
        assertTrue(decorated !is FixedSuccessorDemandCacheOwner)
        val decoratedWorld = Assumptions.of(world.schema, decorated)
        val keys = input.successorDemand(decoratedWorld).merge(world.schema.requireQueryTypeDef()).keys()
        assertTrue(keys.any { it.field.name == "checkerOnly" })
    }

    @Test
    fun `acyclic type checker and resolver expansions are reusable across demand orderings`() {
        val fixture = recursiveFixture()
        val world = fixture.assumptions
        val uncached = uncachedWorld(world)
        val cache = (world.resolverRegistry as FixedSuccessorDemandCacheOwner).fixedSuccessorDemandCache
        val item = world.schema.requireType("Item") as ViaductSchema.Object
        val value = world.schema.requireObjectField("Item", "value")
        val inputs = listOf("value", "child { marker }", "child { value }").map { fields ->
            fixture.schemas.fragmentFrom("fragment F on Item { $fields }").subselections
        }
        val demands = inputs.flatMap { listOf(Demand.checked(it), Demand.unchecked(it)) }
        val expected = demands.map { it.successorDemandFromConstructionDemand(uncached, setOf(item)).shape() }
        repeat(3) {
            demands.indices.reversed().forEach { index ->
                demands[index].successorDemandFromConstructionDemand(fixture.newAssumptions(), setOf(item)).shape() shouldBe expected[index]
            }
        }
        assertNotNull(cache.get(SuccessorBoundary(field = value, kind = SuccessorBoundaryKind.RESOLVER)))
        assertNotNull(cache.get(SuccessorBoundary(type = item, kind = SuccessorBoundaryKind.TYPE_CHECKER)))
    }

    @Test
    fun `concurrent cold computations produce the same demand as independent local analyses`() {
        val fixture = recursiveFixture()
        val item = fixture.schema.requireType("Item") as ViaductSchema.Object
        val inputs = listOf("value", "child { marker }", "child { value }").map { fields ->
            fixture.schemas.fragmentFrom("fragment F on Item { $fields }").subselections
        }
        val demands = inputs.flatMap { listOf(Demand.checked(it), Demand.unchecked(it)) }
        val expected = demands.map { it.successorDemandFromConstructionDemand(uncachedWorld(fixture.assumptions), setOf(item)).shape() }
        val executor = Executors.newFixedThreadPool(4)
        val barrier = CyclicBarrier(4)
        try {
            val tasks = (0 until 4).map { worker ->
                executor.submit {
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(24) { iteration ->
                        val index = (iteration + worker) % demands.size
                        demands[index].successorDemandFromConstructionDemand(fixture.newAssumptions(), setOf(item)).shape() shouldBe expected[index]
                    }
                }
            }
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}

private fun acyclicFixture(): TestWorld =
    TestWorld.fromDSL(
        """
        extend type Query {
          first: Int @resolver(of: "second", result: 1)
          second: Int @resolver(of: "leaf", result: 2)
          leaf: Int @resolver(result: 3)
          checkerOnly: Int @resolver(result: 4)
        }
        """.trimIndent(),
    )

private fun recursiveFixture(): TestWorld =
    TestWorld.fromDSL(
        """
        extend type Query { item: Item @resolver(result: {}) }
        type Item {
          child: Item @resolver(result: null)
          value: Int @resolver(of: "child { marker }", result: 1)
          marker: Int
          raw: Int
        }
        """.trimIndent(),
        typeCheckers = { schemas ->
            val item = schemas.loweredSchema.requireType("Item") as ViaductSchema.Object
            mapOf(
                item to TypeCheckerResolver.of(
                    item,
                    schemas.loweredSchema.requireQueryTypeDef(),
                    mapOf(
                        "raw" to ResolverFragmentTemplates(
                            schemas.fragmentFrom("fragment F on Item { marker raw }").materializeSelections,
                            materializeSelectionForestOf(),
                        ),
                    ),
                ) { _, _ -> CheckerResult.Success },
            )
        },
    )

private fun uncachedWorld(world: Assumptions): Assumptions = Assumptions.of(world.schema, object : ResolverRegistry by world.resolverRegistry {}, world.selectiveResolvers)

private data class SelectionShape(
    val condition: InclusionCondition,
    val children: ForestShape,
)

private typealias ForestShape = Map<ViaductSchema.Object, Map<ObjectEngineResult.ObjectKey, SelectionShape>>

/** Compare only explicit normalization, canonical keys, guards, and descendants, never forest equality. */
private fun SelectionForest.shape(): ForestShape {
    val types = linkedSetOf<ViaductSchema.Object>()
    forEach { types += it.possibleTypes }
    return types.associateWith { type ->
        merge(type).byKey().mapValues { (_, selection) ->
            SelectionShape(selection.inclusionCondition, selection.subselections.shape())
        }
    }
}
