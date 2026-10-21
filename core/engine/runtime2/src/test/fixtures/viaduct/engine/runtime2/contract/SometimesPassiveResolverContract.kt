package viaduct.engine.runtime2.contract

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation

/** Contract for active fields exceptionally supplied by an ancestor resolver output. */
interface SometimesPassiveResolverContract : ResolverContract {
    @Test
    fun `ancestor output supplies an active field instead of its standard resolver`() {
        val standardApplications = mutableListOf<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                if (field.containingDef.name == "Item") {
                    standardApplications += field.name
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {computed: 7})
                    }

                    type Item {
                      computed: Int! @resolver(result: 99)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val result = resolveAndValidate(testWorld, "query { item { computed } }", resolverObserver = invocationObserver)
        val item =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "item")).get(),
            )

        assertEquals(7, item.getCell(world.schema.contractKey("Item", "computed")).get())
        assertEquals(emptyList(), standardApplications)
    }

    @Test
    fun `ancestor output supplies active fields at successive descendant fringes`() {
        val standardApplications = mutableListOf<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                if (field.containingDef.name != "Query") {
                    standardApplications +=
                        "${field.containingDef.name}/${field.name}"
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item!
                        @resolver(result: {computed: {leaf: 7}})
                    }

                    type Item {
                      computed: Computed!
                        @resolver(result: {leaf: 99})
                    }

                    type Computed {
                      leaf: Int! @resolver(result: 101)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val result = resolveAndValidate(testWorld, "query { item { computed { leaf } } }", resolverObserver = invocationObserver)
        val item =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "item")).get(),
            )
        val computed =
            assertIs<ObjectEngineResult>(
                item.getCell(world.schema.contractKey("Item", "computed")).get(),
            )

        assertEquals(7, computed.getCell(world.schema.contractKey("Computed", "leaf")).get())
        assertEquals(emptyList(), standardApplications)
    }
}

/** Sometimes-passive contract for resolvers that support nonempty standard object fragments. */
interface SometimesPassiveObjectFragmentResolverContract : ResolverContract {
    @Test
    fun `supplied intermediate dependency retains requested children and suppresses transitive inputs`() {
        for (supplied in listOf(true, false)) {
            val providerCalls = AtomicInteger()
            val applications = ConcurrentLinkedQueue<String>()
            val observer = object : CorrectnessResolverObserver() {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    super.onResolverInvocation(observation)
                    applications += "${observation.field.containingDef.name}/${observation.field.name}"
                }
            }
            val testWorld = TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL = """
                    type Query { item: Item constant: Int base(value: Int): Int }
                    type Item { leftResult: Int rightResult: Int middle: Leaf fixed: Int seed(value: Int): Int }
                    type Leaf { left: Int right: Int }
                """.trimIndent(),
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    mapOf(
                        schema.requireObjectField("Query", "item") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                            schema.objectOf("Item") {
                                if (supplied) {
                                    "middle" setTo schema.objectOf("Leaf") {
                                        "left" setTo 7
                                        "right" setTo 9
                                    }
                                }
                            }
                        },
                        schema.requireObjectField("Item", "leftResult") to fieldResolverOf(
                            schemas.fragmentFrom("fragment Input on Item { middle { left } }"),
                        ) { input, _ -> (input.selectionValues().getValue("middle") as EngineObjectData.Sync).selectionValues().getValue("left") },
                        schema.requireObjectField("Item", "rightResult") to fieldResolverOf(
                            schemas.fragmentFrom("fragment Input on Item { middle { right } }"),
                        ) { input, _ -> (input.selectionValues().getValue("middle") as EngineObjectData.Sync).selectionValues().getValue("right") },
                        schema.requireObjectField("Item", "middle") to fieldResolverOf(
                            objectFragment = schemas.fragmentFrom("fragment Input on Item { fixed seed(value: 4) }"),
                            queryFragment = schemas.fragmentFrom("fragment Input on Query { constant base(value: ${'$'}value) }"),
                        ) { input, query, _ ->
                            schema.objectOf("Leaf") {
                                "left" setTo input.selectionValues().getValue("seed")
                                "right" setTo query.selectionValues().getValue("base")
                            }
                        }.withVariablesProvider(setOf("value")) {
                            providerCalls.incrementAndGet()
                            mapOf("value" to 4)
                        },
                        schema.requireObjectField("Item", "fixed") to fieldResolverOf(schema.emptyFragmentOf("Item")) { _, _ -> 3 },
                        schema.requireObjectField("Item", "seed") to fieldResolverOf(schema.emptyFragmentOf("Item")) { _, args -> args.fieldValues.getValue("value") },
                        schema.requireObjectField("Query", "constant") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 23 },
                        schema.requireObjectField("Query", "base") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, args -> args.fieldValues.getValue("value") },
                    )
                },
            )
            val result = resolveAndValidate(testWorld, "query { item { leftResult rightResult } }", resolverObserver = observer)
            val item = assertIs<ObjectEngineResult>(result.getCell(testWorld.schema.contractKey("Query", "item")).get())

            assertEquals(if (supplied) 7 else 4, item.getCell(testWorld.schema.contractKey("Item", "leftResult")).get())
            assertEquals(if (supplied) 9 else 4, item.getCell(testWorld.schema.contractKey("Item", "rightResult")).get())
            assertEquals(if (supplied) 0 else 1, providerCalls.get())
            val expected = mutableListOf("Query/item", "Item/leftResult", "Item/rightResult")
            if (!supplied) expected += listOf("Item/middle", "Item/fixed", "Item/seed", "Query/constant", "Query/base")
            assertEquals(expected.sorted(), applications.sorted())
        }
    }

    @Test
    fun `ancestor-supplied active field does not activate its standard resolver demand`() {
        val standardApplications = mutableListOf<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                if (field.containingDef.name == "Item") {
                    standardApplications += field.name
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {computed: 7})
                    }

                    type Item {
                      seed: Int! @resolver(result: 1)
                      computed: Int!
                        @resolver(of: "seed", result: 99)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val result = resolveAndValidate(testWorld, "query { item { computed } }", resolverObserver = invocationObserver)
        val item =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "item")).get(),
            )

        assertEquals(7, item.getCell(world.schema.contractKey("Item", "computed")).get())
        assertEquals(emptyList(), standardApplications)
    }

    @Test
    fun `omitted active field uses its standard resolver and ancestor-supplied demand`() {
        val standardApplications = mutableListOf<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                if (field.containingDef.name == "Item") {
                    standardApplications += field.name
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {seed: 3})
                    }

                    type Item {
                      seed: Int! @resolver(result: 1)
                      computed: Int!
                        @resolver(of: "seed", result: 99)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val result = resolveAndValidate(testWorld, "query { item { computed } }", resolverObserver = invocationObserver)
        val item =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "item")).get(),
            )

        assertEquals(99, item.getCell(world.schema.contractKey("Item", "computed")).get())
        assertEquals(listOf("computed"), standardApplications)
    }
}

/** Sometimes-passive contract for resolvers that support FromObjectField variables. */
interface SometimesPassiveObjectPathResolverContract : ResolverContract {
    @Test
    fun `reads a provider below an ancestor-supplied active field`() {
        val standardApplications = mutableListOf<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                if (
                    field.containingDef.name == "Item" &&
                    field.name in setOf("provider", "seed")
                ) {
                    standardApplications += field.name
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item!
                        @resolver(result: {provider: {value: 11}})
                    }

                    type Item {
                      result: Int!
                        @resolver(
                          of: "provider { value } consume(value: ${'$'}value)"
                          pathVars: [{name: "value", path: ["provider", "value"]}]
                          result: "sum(consume)"
                        )
                      provider: Provider!
                        @resolver(of: "seed", result: {value: 99})
                      seed: Int! @resolver(result: 1)
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }

                    type Provider {
                      value: Int!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val resolved = resolveAndValidate(testWorld, "query { item { result } }", resolverObserver = invocationObserver)
        val item =
            assertIs<ObjectEngineResult>(
                resolved.getCell(world.schema.contractKey("Query", "item")).get(),
            )

        assertEquals(11, item.getCell(world.schema.contractKey("Item", "result")).get())
        assertEquals(emptyList(), standardApplications)
    }

    @Test
    fun `does not validate object-path bindings for a source-owned resolver occurrence`() {
        val standardApplications = mutableListOf<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                if (field.containingDef.name == "Item") {
                    standardApplications += field.name
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {computed: 11})
                    }

                    type Item {
                      computed: Int!
                        @resolver(
                          of: "provider consume(value: ${'$'}value)"
                          pathVars: [{name: "value", path: ["provider"]}]
                          result: "sum(consume)"
                        )
                      provider: Int! @resolver(result: 99)
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val resolution =
            resolveAndValidateObserved(testWorld, "query { item { computed } }", resolverObserver = invocationObserver)
        val resolved = resolution.result
        val item =
            assertIs<ObjectEngineResult>(
                resolved.getCell(world.schema.contractKey("Query", "item")).get(),
            )
        resolved.validateFromFieldBindings(resolution.operation, emptySet())

        assertEquals(11, item.getCell(world.schema.contractKey("Item", "computed")).get())
        assertEquals(emptyList(), standardApplications)
    }
}

/** One-shot selective witness for conservative standard demand and dynamic output ownership. */
interface SometimesPassiveSelectiveResolverContract : ResolverContract {
    @Test
    fun `ancestor is invoked once with standard demand before supplying the active field`() {
        val events = mutableListOf<String>()
        var itemDemand: Set<String>? = null
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                val selections = observation.suppliedDemand
                if (selections != null) {
                    events += "observer ${field.containingDef.name}/${field.name}"
                    if (field.containingDef.name == "Query" && field.name == "item") {
                        itemDemand =
                            linkedSetOf<String>().also { fields ->
                                selections.forEach { selection ->
                                    fields += selection.key.field.name
                                }
                            }
                    }
                }
            }
        }
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    type Item {
                      seed: Int!
                      computed: Int!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireField("Query", "item") to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                function = { _, _ ->
                                    events += "function Query/item"
                                    schema.loweredSchema.objectOf("Item") {
                                        "seed" setTo 3
                                        "computed" setTo 7
                                    }
                                },
                            ),
                        schema.loweredSchema.requireField("Item", "seed") to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Item"),
                                function = { _, _ ->
                                    events += "function Item/seed"
                                    1
                                },
                            ),
                        schema.loweredSchema.requireField("Item", "computed") to
                            fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        "fragment ignored on Item { seed }",
                                    ),
                                function = { _, _ ->
                                    events += "function Item/computed"
                                    99
                                },
                            ),
                    )
                },
            )
        val world = testWorld.assumptions

        val result = resolveAndValidate(testWorld, "query { item { computed } }", resolverObserver = invocationObserver)
        val item =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "item")).get(),
            )

        assertEquals(7, item.getCell(world.schema.contractKey("Item", "computed")).get())
        assertEquals(setOf("computed", "seed"), itemDemand)
        assertEquals(
            listOf(
                "observer Query/item",
                "function Query/item",
            ),
            events,
        )
    }
}
