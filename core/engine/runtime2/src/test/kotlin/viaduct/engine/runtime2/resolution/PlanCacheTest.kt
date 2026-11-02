package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.renameVariableInstances
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.descendants
import viaduct.engine.runtime2.resolution.framework.forestPlanEntries

class PlanCacheTest : ResolutionDispatcherResource {
    @Test
    fun `cached and uncached resolution agree on results and resolver applications`() {
        val world = abstractListWorld()
        val selections = world.schemas.fragmentFrom(QUERY).subselections
        val uncached = resolveCounting(world, selections, usePlanCache = false)
        val cached = resolveCounting(world, selections, usePlanCache = true)
        val repeated = resolveCounting(world, selections, usePlanCache = true)
        assertEquals(mapOf("things" to 1, "label" to 4, "seeds" to 2, "size" to 4), uncached)
        assertEquals(uncached, cached)
        assertEquals(uncached, repeated)
    }

    @Test
    fun `list elements of one concrete type share one closure template`() {
        val world = abstractListWorld()
        val selections = world.schemas.fragmentFrom(QUERY).subselections
        resolveCounting(world, selections, usePlanCache = true)

        val root = selections.closureTemplates().single()
        val thingsKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "things"), emptyMap())
        val things = root.objectSide.constructionDemand.descendants(thingsKey).checked
        val elementTemplates = things.closureTemplates()
        assertEquals(setOf("Apple", "Pear"), elementTemplates.map { it.type.name }.toSet())
        assertEquals(2, elementTemplates.size, "Four list elements of two concrete types close twice")
        assertEquals(1, things.forestPlanEntries().count { it is SuccessorDemandEntry })

        val apple = elementTemplates.single { it.type.name == "Apple" }
        val seedsKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Apple", "seeds"), emptyMap())
        val seeds = apple.objectSide.constructionDemand.descendants(seedsKey).checked
        assertEquals(listOf("Seed"), seeds.closureTemplates().map { it.type.name }, "Four seed occurrences under two apples close once")

        val entries = things.forestPlanEntries()
        resolveCounting(world, selections, usePlanCache = true)
        assertEquals(entries, things.forestPlanEntries(), "A repeated request reuses every template without recording new ones")
    }

    @Test
    fun `FromArgument variables are cached with instances owned by a placeholder occurrence`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  total(extra: Int!): Int! @resolver(of: "base(value: ${'$'}extra)", result: "sum(base)")
                  base(value: Int!): Int! @resolver(result: "sumplus1(${'$'}value)")
                }
                """.trimIndent(),
            )
        val selections = world.schemas.fragmentFrom("fragment F on Query { total(extra: 2) }").subselections
        val total = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "total"), mapOf("extra" to 2))
        repeat(2) {
            val operation = SharedOperationContext.create(world.newAssumptions(usePrecomputedConstructionDemand = true, usePlanCache = true))
            assertEquals(3, operation.resolveWithTestDispatcher(selections).getCell(total).value.get())
        }
        assertTrue(selections.closureTemplates().single().variableBase != null)
    }

    @Test
    fun `provider-path variables keep closure per occurrence`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result: Int! @resolver(
                    of: "source consume(value: ${'$'}fromSource)"
                    pathVars: [{name: "fromSource", path: ["source"]}]
                    result: "sum(consume)"
                  )
                  source: Int! @resolver(result: 7)
                  consume(value: Int!): Int! @resolver(result: "sumplus1(${'$'}value)")
                }
                """.trimIndent(),
            )
        val selections = world.schemas.fragmentFrom("fragment F on Query { result }").subselections
        val operation = SharedOperationContext.create(world.newAssumptions(usePrecomputedConstructionDemand = true, usePlanCache = true))
        val result = operation.resolveWithTestDispatcher(selections)
        assertEquals(8, result.getCell(ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "result"), emptyMap())).value.get())
        assertTrue(selections.closureTemplates().isEmpty(), "A closure with provider reads is not cached")
    }

    @Test
    fun `FromArgument guards and arguments are renamed per occurrence and agree with uncached resolution`() {
        val world = conditionalListWorld()
        val selections = world.schemas.fragmentFrom(CONDITIONAL_QUERY).subselections
        val uncachedDemand = ConcurrentHashMap<String, MutableList<String>>()
        val uncached = resolveCounting(world, selections, usePlanCache = false, suppliedDemand = uncachedDemand)
        val cachedDemand = ConcurrentHashMap<String, MutableList<String>>()
        val cached = resolveCounting(world, selections, usePlanCache = true, suppliedDemand = cachedDemand)
        val repeatedDemand = ConcurrentHashMap<String, MutableList<String>>()
        val repeated = resolveCounting(world, selections, usePlanCache = true, suppliedDemand = repeatedDemand)
        assertEquals(mapOf("things" to 1, "label" to 5, "weight" to 2, "total" to 2, "scaled" to 2), uncached)
        assertEquals(uncached, cached)
        assertEquals(uncached, repeated)
        assertEquals(uncachedDemand.sorted(), cachedDemand.sorted(), "Tenants receive the same demand with the cache on")
        assertEquals(uncachedDemand.sorted(), repeatedDemand.sorted())

        val root = selections.closureTemplates().single()
        val thingsKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "things"), emptyMap())
        val apple = root.objectSide.constructionDemand.descendants(thingsKey).checked.closureTemplates().single { it.type.name == "Apple" }
        assertTrue(apple.variableBase != null, "The Apple template owns renamed FromArgument instances")
    }

    private fun resolveCounting(
        world: TestWorld,
        selections: SelectionForest,
        usePlanCache: Boolean,
        suppliedDemand: MutableMap<String, MutableList<String>>? = null,
    ): Map<String, Int> {
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val observer =
            object : CorrectnessResolverObserver() {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    super.onResolverInvocation(observation)
                    counts.computeIfAbsent(observation.field.name) { AtomicInteger() }.incrementAndGet()
                    suppliedDemand?.let { demand ->
                        synchronized(demand) { demand.getOrPut(observation.field.name, ::mutableListOf) += (observation.suppliedDemand?.render() ?: "-") }
                    }
                }
            }
        val operation =
            SharedOperationContext.create(
                world.newAssumptions(usePrecomputedConstructionDemand = true, usePlanCache = usePlanCache),
                resolverObserver = observer,
            )
        val result = operation.resolveWithTestDispatcher(selections)
        assertTrue(result.correctResolution(operation, selections.merge(world.schema.requireQueryTypeDef())))
        return counts.mapValues { (_, count) -> count.get() }
    }
}

private fun SelectionForest.closureTemplates(): List<ClosureTemplate> = forestPlanEntries().filterIsInstance<ClosureTemplate>()

private fun Map<String, List<String>>.sorted(): Map<String, List<String>> = toSortedMap().mapValues { (_, renders) -> renders.sorted() }

/** Renders demand with variable instances reduced to their templates, so equal shapes compare equal across occurrences. */
private fun SelectionForest.render(): String {
    val template: (Arguments.Variable) -> Arguments.Variable = { variable -> Arguments.Variable.of(variable.target, variable.variableName) }
    val parts = mutableListOf<String>()
    forEach { selection ->
        val key = selection.key.renameVariableInstances(template)
        val condition = selection.inclusionCondition.mapVariables { if (it.isTemplate) it else template(it) }
        val guard = if (condition === InclusionCondition.Always) "" else " if $condition"
        parts += "${key.field.containingDef.name}.${key.field.name}${key.arguments}$guard{${selection.subselections.render()}}"
    }
    return parts.sorted().joinToString(" ")
}

private const val CONDITIONAL_QUERY =
    "fragment F on Query { things { ... on Apple { yes: label(withWeight: true) no: label(withWeight: false) total(n: 4) } ... on Pear { label(withWeight: true) } } }"

private fun conditionalListWorld(): TestWorld =
    TestWorld.fromDSL(
        """
        extend type Query {
          things: [Thing!]! @resolver(
            result: [
              {__typename: "Apple", base: 1},
              {__typename: "Pear", height: 5},
              {__typename: "Apple", base: 2}
            ]
          )
        }
        interface Thing { label(withWeight: Boolean!): Int }
        type Apple implements Thing {
          base: Int
          weight: Int @resolver(result: 3)
          label(withWeight: Boolean!): Int @resolver(of: "base weight @include(if: ${'$'}withWeight)", result: "sum(base)")
          scaled(by: Int!): Int @resolver(result: "sumplus1(${'$'}by)")
          total(n: Int!): Int @resolver(of: "scaled(by: ${'$'}n)", result: "sum(scaled)")
        }
        type Pear implements Thing {
          height: Int
          label(withWeight: Boolean!): Int @resolver(of: "height", result: "sum(height)")
        }
        """.trimIndent(),
    )

private const val QUERY = "fragment F on Query { things { label ... on Apple { weight seeds { size } } ... on Pear { height } } }"

private fun abstractListWorld(): TestWorld =
    TestWorld.fromDSL(
        """
        extend type Query {
          things: [Thing!]! @resolver(
            result: [
              {__typename: "Apple", weight: 3},
              {__typename: "Pear", height: 5},
              {__typename: "Apple", weight: 4},
              {__typename: "Pear", height: 6}
            ]
          )
        }
        interface Thing { label: Int }
        type Apple implements Thing {
          label: Int @resolver(of: "weight", result: "sumplus1(weight)")
          weight: Int
          seeds: [Seed!]! @resolver(result: [{count: 1}, {count: 2}])
        }
        type Pear implements Thing {
          label: Int @resolver(of: "height", result: "sum(height, height)")
          height: Int
        }
        type Seed {
          count: Int
          size: Int @resolver(of: "count", result: "sumplus1(count)")
        }
        """.trimIndent(),
    )
