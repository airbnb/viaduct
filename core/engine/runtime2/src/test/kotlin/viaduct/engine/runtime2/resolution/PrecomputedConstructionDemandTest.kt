package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.selectionValues
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromArgument
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.OrchestrationConstructionDemand
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.ResolverObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class PrecomputedConstructionDemandTest : ResolutionDispatcherResource {
    @Test
    fun `shared Query closures retain both input roots and argument-distinct provider occurrences`() {
        for (precomputed in listOf(false, true)) {
            val providers = ConcurrentLinkedQueue<Int>()
            val calls = ConcurrentLinkedQueue<String>()
            val fixture = TestWorld.fromSDL(
                schemaSDL = """
                    type Query { result: Int left: Int right: Int shared(seed: Int): Int base(value: Int): Int item: Item }
                    type Item { objectValue: Int queryValue: Int }
                """.trimIndent(),
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    val empty = schema.emptyFragmentOf("Query")
                    buildMap {
                        put(
                            schema.requireObjectField("Query", "result"),
                            fieldResolverOf(empty, schemas.fragmentFrom("fragment F on Query { left right }")) { _, query, _ ->
                                query.selectionValues().values.sumOf { it as Int }
                            },
                        )
                        for ((name, seed) in listOf("left" to 1, "right" to 2)) {
                            put(
                                schema.requireObjectField("Query", name),
                                fieldResolverOf(empty, schemas.fragmentFrom("fragment F on Query { shared(seed: $seed) }")) { _, query, _ ->
                                    query.selectionValues().getValue("shared")
                                },
                            )
                        }
                        put(
                            schema.requireObjectField("Query", "shared"),
                            fieldResolverOf(
                                schemas.fragmentFrom("fragment F on Query { item { objectValue } }"),
                                schemas.fragmentFrom("fragment F on Query { item { queryValue } base(value: ${'$'}provided) }"),
                            ) { input, query, _ ->
                                val objectItem = input.selectionValues().getValue("item") as EngineObjectData.Sync
                                val queryItem = query.selectionValues().getValue("item") as EngineObjectData.Sync
                                (objectItem.selectionValues().getValue("objectValue") as Int) +
                                    (queryItem.selectionValues().getValue("queryValue") as Int) +
                                    (query.selectionValues().getValue("base") as Int)
                            }.withVariablesProvider(setOf("provided")) { arguments ->
                                val seed = arguments.fieldValues.getValue("seed") as Int
                                providers += seed
                                mapOf("provided" to seed)
                            },
                        )
                        put(schema.requireObjectField("Query", "base"), fieldResolverOf(empty) { _, arguments -> arguments.fieldValues.getValue("value") })
                        put(
                            schema.requireObjectField("Query", "item"),
                            fieldResolverOf(empty) { _, _ ->
                                schema.objectOf("Item") {
                                    "objectValue" setTo 10
                                    "queryValue" setTo 20
                                }
                            },
                        )
                    }
                },
            )
            val operation = SharedOperationContext.create(
                fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed),
                resolverObserver = object : ResolverObserver {
                    override fun onResolverInvocation(observation: ResolverInvocationObservation) {
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
              outer: Int @resolver(of: "result", result: 1)
              result: Int @resolver(
                of: "source fixed branch { one leaf(value: ${'$'}provided) }"
                pathVars: [{name: "provided", path: ["source"]}]
                result: 1
              )
              source: Int @resolver(result: 7)
              fixed: Int @resolver(result: 5)
              branch: Branch @resolver(result: {one: 3})
            }
            type Branch {
              one: Int
              leaf(value: Int): Int @resolver(result: "value(${'$'}value)")
            }
            """.trimIndent(),
        )
        val resultField = fixture.schema.requireObjectField("Query", "result")
        val template = requireNotNull(fixture.resolverRegistry.precomputedConstructionDemand(resultField))
        assertEquals(setOf("source", "fixed", "branch"), template.objectDemand.reusableDemand.keys().map { it.field.name }.toSet())
        val branch = template.objectDemand.reusableDemand.byKey().values.single { it.key.field.name == "branch" }
        assertEquals("one", branch.subselections.single().key.field.name)
        assertEquals("branch", template.objectDemand.templatedDemand.single().key.field.name)
        val outer = requireNotNull(fixture.resolverRegistry.precomputedConstructionDemand(fixture.schema.requireObjectField("Query", "outer")))
        assertTrue(outer.objectDemand.dependencies.values.any { it === template })
        val type = fixture.schema.requireQueryTypeDef()
        val root = ObjectEngineResult.of(type, mutable = true)
        val query = ObjectEngineResult.of(type, mutable = true)
        val closed = fixture.resolverRegistry.createRootQueryInput().closePrecomputedConstructionDemand(
            fixture.assumptions,
            OEROccurrence(root, emptyList(), root),
            OEROccurrence(query, emptyList(), query),
            OrchestrationConstructionDemand(Demand.checked(fixture.schemas.fragmentFrom("fragment F on Query { outer }").subselections), Demand.EMPTY),
        )
        val reads = closed.objectRooted.variableProviderReadsByResolverOccurrence.values.flatten()
        assertEquals(1, reads.size)
        assertTrue(reads.single().providerResult === root)
        assertEquals(listOf("source"), reads.single().definition.path.map { it.key.field.name })
    }

    @Test
    fun `closure reserves distinct future keys without invoking providers or resolvers`() {
        for (precomputed in listOf(false, true)) {
            val providers = ConcurrentLinkedQueue<Int>()
            val calls = ConcurrentLinkedQueue<String>()
            val fixture = TestWorld.fromSDL(
                schemaSDL = "type Query { consumer(seed: Int): Int middle(value: Int): Int base(value: Int): Int }",
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    mapOf(
                        schema.requireObjectField("Query", "consumer") to fieldResolverOf(
                            schema.emptyFragmentOf("Query"),
                            schemas.fragmentFrom("fragment F on Query { literal: middle(value: 7) symbolic: middle(value: ${'$'}value) }"),
                        ) { _, query, _ ->
                            calls += "consumer"
                            query.selectionValues().values.sumOf { it as Int }
                        },
                        schema.requireObjectField("Query", "middle") to fieldResolverOf(
                            schema.emptyFragmentOf("Query"),
                            schemas.fragmentFrom("fragment F on Query { base(value: ${'$'}provided) }"),
                        ) { _, query, _ ->
                            calls += "middle"
                            query.selectionValues().getValue("base")
                        }.withVariablesProvider(setOf("provided")) { arguments ->
                            val value = arguments.fieldValues.getValue("value") as Int
                            providers += value
                            mapOf("provided" to value * 2)
                        },
                        schema.requireObjectField("Query", "base") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                            calls += "base"
                            arguments.fieldValues.getValue("value")
                        },
                    )
                },
                variableProviders = { schemas ->
                    val field = schemas.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(Arguments.Variable.of(field, "value") to schemas.loweredSchema.fromArgument(field, "seed"))
                },
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
            val fixture = TestWorld.fromSDL(
                schemaSDL = """
                    type Query { item: Item seed: Int backing: Int }
                    type Item { result: Int supplied: Int middle: Int leaf: Int raw: Int unused: Int }
                """.trimIndent(),
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    mapOf(
                        schema.requireObjectField("Query", "item") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                            schema.objectOf("Item") {
                                "supplied" setTo 17
                                "raw" setTo 19
                            }
                        },
                        schema.requireObjectField("Item", "result") to fieldResolverOf(
                            schemas.fragmentFrom("fragment F on Item { supplied middle }"),
                            schemas.fragmentFrom("fragment F on Query { seed }"),
                        ) { _, _, _ -> 23 },
                        schema.requireObjectField("Item", "supplied") to fieldResolverOf(schemas.fragmentFrom("fragment F on Item { unused }")) { _, _ -> error("Source owns supplied") },
                        schema.requireObjectField("Item", "middle") to fieldResolverOf(schemas.fragmentFrom("fragment F on Item { leaf }")) { _, _ -> 2 },
                        schema.requireObjectField("Item", "leaf") to fieldResolverOf(schema.emptyFragmentOf("Item")) { _, _ -> 3 },
                        schema.requireObjectField("Query", "seed") to fieldResolverOf(schemas.fragmentFrom("fragment F on Query { backing }")) { _, _ -> 5 },
                        schema.requireObjectField("Query", "backing") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    )
                },
                fieldCheckers = { schemas ->
                    listOf("result", "raw").associate { name ->
                        val field = schemas.loweredSchema.requireObjectField("Item", name)
                        field to FieldCheckerResolver.of(
                            field,
                            schemas.loweredSchema.requireQueryTypeDef(),
                            mapOf(
                                "input" to ResolverFragmentTemplates(
                                    schemas.fragmentFrom(if (name == "result") "fragment F on Item { raw }" else "fragment F on Item { unused }").materializeSelections,
                                    materializeSelectionForestOf()
                                )
                            ),
                        ) { _, _, _ ->
                            checks += name
                            CheckerResult.Success
                        }
                    }
                },
            )
            val calls = ConcurrentLinkedQueue<String>()
            val operation = SharedOperationContext.create(
                fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed),
                resolverObserver = object : ResolverObserver {
                    override fun onResolverInvocation(observation: ResolverInvocationObservation) {
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
