package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.referenceConstructionDemand
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.OrchestrationConstructionDemand
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class PrecomputedConstructionDemandTest : ResolutionDispatcherResource {
    @Test
    fun `shared Query closures retain both input roots and argument-distinct provider occurrences`() {
        for (precomputed in listOf(false, true)) {
            val providers = ConcurrentLinkedQueue<Int>()
            val calls = ConcurrentLinkedQueue<String>()
            val fixture = TestWorld.fromDSL(
                """
                extend type Query {
                  result: Int @resolver(qf: "left right", script: "queryValue.left + queryValue.right")
                  left: Int @resolver(qf: "shared(seed: 1)", script: "queryValue.shared")
                  right: Int @resolver(qf: "shared(seed: 2)", script: "queryValue.shared")
                  shared(seed: Int): Int @resolver(
                    of: "item { objectValue }"
                    qf: "item { queryValue } base(value: ${'$'}provided)"
                    vars: {names: ["provided"], script: "providers.add(seed); {'provided': seed}"}
                    script: "objectValue.item.objectValue + queryValue.item.queryValue + queryValue.base"
                  )
                  base(value: Int): Int @resolver(script: "value")
                  item: Item @resolver(script: "{'objectValue': 10, 'queryValue': 20}")
                }
                type Item { objectValue: Int queryValue: Int }
                """.trimIndent(),
                scriptBindings = mapOf("providers" to providers),
            )
            val operation = SharedOperationContext.create(
                fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed),
                resolutionObserver = object : ResolutionObserver {
                    override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                        calls += observation.field.name
                    }
                },
            )

            val result = operation.resolveWithTestDispatcher(fixture.schemas.fragmentFrom("fragment F on Query { result }").subselections)

            assertEquals(63, result.getCell(ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("Query", "result"), emptyMap())).value.get())
            assertEquals(listOf(1, 2), providers.toList().sorted())
            assertEquals(listOf("base", "base", "item", "left", "result", "right", "shared", "shared"), calls.toList().sorted())
        }
    }

    @Test
    fun `FromField owners and their dependents retain static siblings and ancestor spines`() {
        val fixture = TestWorld.fromDSL(
            """
            extend type Query {
              outer: Int @resolver(of: "result", script: "1")
              result: Int @resolver(
                of: "source fixed branch { one leaf(value: ${'$'}provided) }"
                pathVars: [{name: "provided", path: ["source"]}]
                script: "1"
              )
              source: Int @resolver(script: "7")
              fixed: Int @resolver(script: "5")
              branch: Branch @resolver(script: "{'one': 3}")
            }
            type Branch {
              one: Int
              leaf(value: Int): Int @resolver(script: "value")
            }
            """.trimIndent(),
        )
        val resultField = fixture.schema.requireObjectField("Query", "result")
        val template = requireNotNull(fixture.resolverRegistry.precomputedConstructionDemand(resultField, context = null))
        assertEquals(setOf("source", "fixed", "branch"), template.objectDemand.reusableDemand.keys().map { it.field.name }.toSet())
        val branch = template.objectDemand.reusableDemand.byKey().values.single { it.key.field.name == "branch" }
        assertEquals("one", branch.subselections.single().key.field.name)
        assertEquals("branch", template.objectDemand.templatedDemand.single().key.field.name)
        val outer = requireNotNull(fixture.resolverRegistry.referenceConstructionDemand(fixture.schema.requireObjectField("Query", "outer")))
        assertTrue(outer.objectDemand.dependencies.values.any { it.direct === template })
        val type = fixture.schema.requireQueryTypeDef()
        val root = ObjectEngineResult.of(type, mutable = true)
        val query = ObjectEngineResult.of(type, mutable = true)
        val closed = fixture.resolverRegistry.createRootQueryInput().closePrecomputedConstructionDemand(
            fixture.assumptions,
            OEROccurrence(root, emptyList(), root),
            OEROccurrence(query, emptyList(), query),
            OrchestrationConstructionDemand(Demand.checked(fixture.schemas.fragmentFrom("fragment F on Query { outer }").subselections), Demand.EMPTY),
            engineExecutionContext = null,
        )
        val reads = closed.objectRooted.variableProviderReadsByResolverOccurrence.values.flatten()
        assertEquals(1, reads.size)
        assertTrue(reads.single().providerRoot.startingResult === root)
        assertEquals(listOf("source"), reads.single().definition.path.map { it.key.field.name })
    }

    @Test
    fun `closure reserves distinct future keys without invoking providers or resolvers`() {
        for (precomputed in listOf(false, true)) {
            val providers = ConcurrentLinkedQueue<Int>()
            val calls = ConcurrentLinkedQueue<String>()
            val fixture = TestWorld.fromDSL(
                """
                extend type Query {
                  consumer(seed: Int): Int @resolver(
                    qf: "literal: middle(value: 7) symbolic: middle(value: ${'$'}value)"
                    variables: [{name: "value", fromArgument: ["seed"]}]
                    script: "calls.add('consumer'); queryValue.literal + queryValue.symbolic"
                  )
                  middle(value: Int): Int @resolver(
                    qf: "base(value: ${'$'}provided)"
                    vars: {names: ["provided"], script: "providers.add(value); {'provided': value * 2}"}
                    script: "calls.add('middle'); queryValue.base"
                  )
                  base(value: Int): Int @resolver(script: "calls.add('base'); value")
                }
                """.trimIndent(),
                scriptBindings = mapOf("providers" to providers, "calls" to calls),
            )
            val world = fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed)
            val type = fixture.schema.requireQueryTypeDef()
            val root = ObjectEngineResult.of(type, mutable = true)
            val query = ObjectEngineResult.of(type, mutable = true)
            val selections = fixture.schemas.fragmentFrom("fragment F on Query { consumer(seed: 7) }").subselections
            val closed = fixture.resolverRegistry.createRootQueryInput().closeSelectedConstructionDemand(
                world,
                OEROccurrence(root, emptyList(), root),
                OEROccurrence(query, emptyList(), query),
                OrchestrationConstructionDemand(Demand.checked(selections), Demand.EMPTY),
                engineExecutionContext = null,
            )
            val middle = closed.queryRooted.fieldResolverOccurrences.values.filter { it.selection.key.field.name == "middle" }
            val base = closed.queryRooted.fieldResolverOccurrences.values.filter { it.selection.key.field.name == "base" }
            assertEquals(2, middle.size)
            assertEquals(2, base.size)
            assertEquals(2, base.map { it.selection.key }.toSet().size)
            assertTrue(base.all { it.selection.key !is ObjectEngineResult.GroundKey })
            assertTrue(providers.toList().isEmpty())
            assertTrue(calls.toList().isEmpty())
            val result = SharedOperationContext.create(world).resolveWithTestDispatcher(selections)
            assertEquals(28, result.getCell(ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("Query", "consumer"), mapOf("seed" to 7))).value.get())
            assertEquals(listOf(7, 7), providers.toList())
            assertEquals(listOf("base", "base", "consumer", "middle", "middle"), calls.toList().sorted())
        }
    }

    @Test
    fun `both closures preserve source activation Query inputs and raw checker inputs`() {
        for (precomputed in listOf(false, true)) {
            val checks = ConcurrentLinkedQueue<String>()
            val fixture = TestWorld.fromDSL(
                """
                extend type Query {
                  item: Item @resolver(script: "{'supplied': 17, 'raw': 19}")
                  seed: Int @resolver(of: "backing", script: "5")
                  backing: Int @resolver(script: "7")
                }
                type Item {
                  result: Int
                    @resolver(of: "supplied middle", qf: "seed", script: "23")
                    @checker(inputs: [{name: "input", of: "raw"}], script: "checks.add('result'); allow")
                  supplied: Int @resolver(of: "unused", script: "throw 'Source owns supplied'")
                  middle: Int @resolver(of: "leaf", script: "2")
                  leaf: Int @resolver(script: "3")
                  raw: Int @checker(inputs: [{name: "input", of: "unused"}], script: "checks.add('raw'); allow")
                  unused: Int
                }
                """.trimIndent(),
                scriptBindings = mapOf("checks" to checks),
            )
            val calls = ConcurrentLinkedQueue<String>()
            val operation = SharedOperationContext.create(
                fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed),
                resolutionObserver = object : ResolutionObserver {
                    override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                        calls += "${observation.field.containingDef.name}/${observation.field.name}"
                    }
                },
            )
            val result = operation.resolveWithTestDispatcher(fixture.schemas.fragmentFrom("fragment F on Query { item { result } }").subselections)
            val item = result.getCell(ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("Query", "item"), emptyMap())).value.get() as ObjectEngineResult
            assertEquals(23, item.getCell(ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("Item", "result"), emptyMap())).value.get())
            assertEquals(listOf("result"), checks.toList())
            assertEquals(listOf("Query/item", "Item/result", "Item/middle", "Item/leaf", "Query/seed", "Query/backing").sorted(), calls.toList().sorted())
        }
    }
}
