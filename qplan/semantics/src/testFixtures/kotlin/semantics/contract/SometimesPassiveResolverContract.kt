package semantics.contract

import kotlin.test.assertEquals
import kotlin.test.assertIs
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.requireField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import org.junit.jupiter.api.Test
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.ResolverInvocationObservation

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

        val result = resolveAndValidate(world, "query { item { computed } }", resolverObserver = invocationObserver)
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

        val result = resolveAndValidate(world, "query { item { computed { leaf } } }", resolverObserver = invocationObserver)
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

        val result = resolveAndValidate(world, "query { item { computed } }", resolverObserver = invocationObserver)
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

        val result = resolveAndValidate(world, "query { item { computed } }", resolverObserver = invocationObserver)
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

        val resolved = resolveAndValidate(world, "query { item { result } }", resolverObserver = invocationObserver)
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
            resolveAndValidateObserved(world, "query { item { computed } }", resolverObserver = invocationObserver)
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
                        schema.requireField("Query", "item") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ ->
                                    events += "function Query/item"
                                    schema.objectOf("Item") {
                                        "seed" setTo 3
                                        "computed" setTo 7
                                    }
                                },
                            ),
                        schema.requireField("Item", "seed") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Item"),
                                function = { _, _ ->
                                    events += "function Item/seed"
                                    1
                                },
                            ),
                        schema.requireField("Item", "computed") to
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

        val result = resolveAndValidate(world, "query { item { computed } }", resolverObserver = invocationObserver)
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
