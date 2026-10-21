package viaduct.engine.runtime2.resolvers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.get
import viaduct.engine.runtime2.contract.selectionValues
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineObjectDataEntry
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.VariablesProviderFunction
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromArgument
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.model.usedVariables
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.OrchestrationConstructionDemand
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.ResolverObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver06.SiblingDependencyLogic
import viaduct.engine.runtime2.resolvers.resolver06.resolve
import viaduct.graphql.schema.ViaductSchema

class ConstructionDemandClosureTest {
    @Test
    fun `distinct normalized symbolic and literal seeds coalesce after grounding`() {
        for (precomputed in listOf(false, true)) {
            val providerCalls = mutableListOf<Int>()
            val fixture = TestWorld.fromSDL(
                selectiveResolvers = false,
                schemaSDL = "type Query { consumer(seed: Int): Int middle(value: Int): Int base(value: Int): Int }",
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    mapOf(
                        schema.requireObjectField("Query", "consumer") to fieldResolverOf(
                            objectFragment = schema.emptyFragmentOf("Query"),
                            queryFragment = schemas.fragmentFrom("fragment Input on Query { literal: middle(value: 7) symbolic: middle(value: ${'$'}value) }"),
                        ) { _, query, _ ->
                            val values = query.selectionValues()
                            (values.getValue("literal") as Int) + (values.getValue("symbolic") as Int)
                        },
                        schema.requireObjectField("Query", "middle") to fieldResolverOf(
                            objectFragment = schema.emptyFragmentOf("Query"),
                            queryFragment = schemas.fragmentFrom("fragment Input on Query { base(value: ${'$'}provided) }"),
                        ) { _, query, _ -> query.selectionValues().getValue("base") }
                            .withVariablesProvider(setOf("provided")) { arguments ->
                                val value = arguments.fieldValues.getValue("value") as Int
                                providerCalls += value
                                mapOf("provided" to value * 2)
                            },
                        schema.requireObjectField("Query", "base") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments -> arguments.fieldValues.getValue("value") },
                    )
                },
                variableProviders = { schemas ->
                    val consumer = schemas.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(Arguments.Variable.of(consumer, "value") to schemas.loweredSchema.fromArgument(consumer, "seed"))
                },
            )
            val consumer = fixture.schema.requireObjectField("Query", "consumer")
            val resolver = fixture.resolverRegistry.resolver(consumer)
            assertEquals(2, resolver.queryFragment.size)
            val template = requireNotNull(fixture.resolverRegistry.precomputedConstructionDemand(consumer)).queryDemand
            assertEquals(1, template.reusableDemand.size)
            assertEquals(1, template.templatedDemand.size)

            val world = fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed)
            val result = SharedOperationContext.create(world).resolve(fixture.schemas.fragmentFrom("fragment F on Query { consumer(seed: 7) }").subselections)

            assertEquals(28, result.getCell(key(world, "Query", "consumer", mapOf("seed" to 7))).get())
            assertEquals(listOf(7), providerCalls)
        }
    }

    @Test
    fun `precomputed closure retains initial provenance and merges transitive Query inputs`() {
        val fixture = precomputedClosureFixture()
        val query = fixture.schema.requireQueryTypeDef()
        val item = fixture.schema.requireType("Item") as ViaductSchema.Object
        val initial =
            OrchestrationConstructionDemand(
                objectRooted =
                    Demand(
                        checked = fixture.schemas.fragmentFrom("fragment F on Query { first item { right } }").subselections,
                        unchecked = fixture.schemas.fragmentFrom("fragment F on Query { second }").subselections,
                        typeCheckDemanded = true,
                    ),
                queryRooted =
                    Demand.unchecked(fixture.schemas.fragmentFrom("fragment F on Query { item { right } }").subselections),
            )

        for (precomputed in listOf(false, true)) {
            val closed = fixture.closeOrchestrationDemand(initial, usePrecomputedConstructionDemand = precomputed)
            assertEquals(setOf("first", "item"), closed.objectRooted.checked.fieldNames())
            assertEquals(setOf("second"), closed.objectRooted.unchecked.fieldNames())
            assertTrue(closed.objectRooted.typeCheckDemanded)
            assertEquals(
                setOf(
                    key(fixture.assumptions, "Query", "middle"),
                    key(fixture.assumptions, "Query", "base", mapOf("value" to 7)),
                    key(fixture.assumptions, "Query", "base", mapOf("value" to 9)),
                    key(fixture.assumptions, "Query", "item"),
                ),
                closed.queryRooted.checked.groundKeys(),
            )
            assertEquals(setOf("item"), closed.queryRooted.unchecked.fieldNames())
            assertEquals(
                setOf("left", "right"),
                closed.queryRooted.checked[key(fixture.assumptions, "Query", "item")].subselections.merge(item).fieldNames(),
            )
            assertEquals(
                setOf("right"),
                closed.objectRooted.checked[key(fixture.assumptions, "Query", "item")].subselections.merge(item).fieldNames(),
            )
        }
        val first = query.fields.single { it.name == "first" }
        val template = fixture.resolverRegistry.precomputedConstructionDemand(first)?.queryDemand
        assertSame(template, fixture.newAssumptions().resolverRegistry.precomputedConstructionDemand(first)?.queryDemand)
        assertEquals(setOf("middle", "item"), requireNotNull(template).reusableDemand.fieldNames())
        assertTrue(template.templatedDemand.isEmpty())
        assertEquals(template.reusableDemand.groundKeys(), template.dependencies.keys)
    }

    @Test
    fun `precomputed closure suppresses passive and errored seeds but still expands Query seeds`() {
        val fixture = precomputedClosureFixture()
        val world = fixture.newAssumptions(usePrecomputedConstructionDemand = true)
        val query = world.schema.requireQueryTypeDef()
        val source = world.schema.objectOf("Query") { "first" setTo 99 }
        val objectRoot = ObjectEngineResult.of(query, emptyMap())
        val queryRoot = ObjectEngineResult.of(query, emptyMap())
        val first = fixture.schemas.fragmentFrom("fragment F on Query { first }").subselections
        val errored = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "second"), Arguments.Error)
        val demand = first + selectionForestOf(Selection.of(errored, setOf(query), selectionForestOf()))

        fun close(queryDemand: Demand<SelectionForest>) =
            source.closeOrchestrationConstructionDemand(
                operation = SharedOperationContext.create(world),
                objectOccurrence = OEROccurrence(objectRoot, emptyList(), objectRoot),
                queryOccurrence = OEROccurrence(queryRoot, emptyList(), queryRoot),
                initialDemand = OrchestrationConstructionDemand(Demand.checked(demand), queryDemand),
            )

        val passive = close(Demand.EMPTY)
        assertEquals(setOf(key(world, "Query", "first"), errored), passive.objectRooted.checked.groundKeys())
        assertTrue(passive.queryRooted.checked.isEmpty())
        assertEquals(setOf("first", "middle", "base", "item"), close(Demand.checked(first)).queryRooted.checked.fieldNames())
    }

    @Test
    fun `precomputed closure preserves object and Query input contributions`() {
        val fixture = queryClosureFixture()
        val initial =
            OrchestrationConstructionDemand.checkedObject(
                fixture.schemas.fragmentFrom("fragment F on Query { entry }").subselections,
            )
        val baseline = fixture.closeOrchestrationDemand(initial)
        val precomputed = fixture.closeOrchestrationDemand(initial, usePrecomputedConstructionDemand = true)
        assertEquals(setOf("entry", "objectOnly", "shared"), precomputed.objectRooted.checked.fieldNames())
        assertEquals(baseline.objectRooted.checked.groundKeys(), precomputed.objectRooted.checked.groundKeys())
        assertEquals(baseline.queryRooted.checked.groundKeys(), precomputed.queryRooted.checked.groundKeys())
        assertEquals(baseline.objectRooted.unchecked.groundKeys(), precomputed.objectRooted.unchecked.groundKeys())
        assertEquals(baseline.queryRooted.unchecked.groundKeys(), precomputed.queryRooted.unchecked.groundKeys())
    }

    @Test
    fun `object closure links a diamond and grounds residuals only for unsupplied dependencies`() {
        for (precomputed in listOf(false, true)) {
            for (supplied in listOf(false, true)) {
                val providerCalls = mutableListOf<Int>()
                val fixture = TestWorld.fromSDL(
                    schemaSDL = """
                        type Query { base(value: Int): Int constant: Int }
                        type Item { leftResult: Int rightResult: Int middle: Leaf fixed: Int seed(value: Int): Int }
                        type Leaf { left: Int right: Int }
                    """.trimIndent(),
                    fieldResolvers = { schemas ->
                        val schema = schemas.loweredSchema
                        mapOf(
                            schema.requireObjectField("Item", "leftResult") to fieldResolverOf(
                                schemas.fragmentFrom("fragment Input on Item { middle { left } }"),
                            ) { _, _ -> error("Closure must not invoke resolvers") },
                            schema.requireObjectField("Item", "rightResult") to fieldResolverOf(
                                schemas.fragmentFrom("fragment Input on Item { middle { right } }"),
                            ) { _, _ -> error("Closure must not invoke resolvers") },
                            schema.requireObjectField("Item", "middle") to fieldResolverOf(
                                objectFragment = schemas.fragmentFrom("fragment Input on Item { fixed seed(value: ${'$'}value) }"),
                                queryFragment = schemas.fragmentFrom("fragment Input on Query { constant base(value: ${'$'}value) }"),
                            ) { _, _, _ -> error("Closure must not invoke resolvers") }.withVariablesProvider(setOf("value")) {
                                providerCalls += 4
                                mapOf("value" to 4)
                            },
                            schema.requireObjectField("Item", "fixed") to fieldResolverOf(schema.emptyFragmentOf("Item")) { _, _ -> 3 },
                            schema.requireObjectField("Item", "seed") to fieldResolverOf(schema.emptyFragmentOf("Item")) { _, _ -> 4 },
                        )
                    },
                )
                val world = fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed)
                val itemType = world.schema.requireType("Item") as ViaductSchema.Object
                val leafType = world.schema.requireType("Leaf") as ViaductSchema.Object
                val source = world.schema.objectOf("Item") {
                    if (supplied) {
                        "middle" setTo world.schema.objectOf("Leaf") {
                            "left" setTo 7
                            "right" setTo 9
                        }
                    }
                }
                val objectRoot = ObjectEngineResult.of(itemType, emptyMap())
                val queryRoot = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), emptyMap())
                val left = world.schema.requireObjectField("Item", "leftResult")
                val right = world.schema.requireObjectField("Item", "rightResult")
                val middle = key(world, "Item", "middle")
                val template = requireNotNull(world.resolverRegistry.precomputedConstructionDemand(left))
                assertSame(
                    template.objectDemand.dependencies.getValue(middle),
                    requireNotNull(world.resolverRegistry.precomputedConstructionDemand(right)).objectDemand.dependencies.getValue(middle),
                )
                assertTrue(providerCalls.isEmpty())
                val closed = source.closeOrchestrationConstructionDemand(
                    operation = SharedOperationContext.create(world),
                    objectOccurrence = OEROccurrence(objectRoot, emptyList(), objectRoot),
                    queryOccurrence = OEROccurrence(queryRoot, emptyList(), queryRoot),
                    initialDemand = OrchestrationConstructionDemand(
                        Demand(
                            checked = fixture.schemas.fragmentFrom("fragment F on Item { leftResult }").subselections,
                            unchecked = fixture.schemas.fragmentFrom("fragment F on Item { rightResult }").subselections,
                            typeCheckDemanded = false,
                        ),
                        Demand.EMPTY,
                    ),
                )
                assertEquals(if (supplied) emptyList() else listOf(4), providerCalls)
                assertEquals(
                    if (supplied) setOf("leftResult", "middle") else setOf("leftResult", "middle", "fixed", "seed"),
                    closed.objectRooted.checked.fieldNames(),
                )
                assertEquals(setOf("rightResult"), closed.objectRooted.unchecked.fieldNames())
                assertEquals(setOf("left", "right"), closed.objectRooted.checked[middle].subselections.merge(leafType).fieldNames())
                assertEquals(if (supplied) emptySet() else setOf("constant", "base"), closed.queryRooted.checked.fieldNames())
                if (!supplied) {
                    assertTrue(key(world, "Item", "seed", mapOf("value" to 4)) in closed.objectRooted.checked.groundKeys())
                    assertTrue(key(world, "Query", "base", mapOf("value" to 4)) in closed.queryRooted.checked.groundKeys())
                }
            }
        }
    }

    @Test
    fun `resolver06 with precomputed closure resolves a Query diamond once per exact key`() {
        val fixture = precomputedClosureFixture()
        for (precomputed in listOf(false, true)) {
            val calls = mutableListOf<ObjectEngineResult.GroundKey>()
            val observer = object : ResolverObserver {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    calls += ObjectEngineResult.GroundKey.of(observation.field, observation.arguments)
                }
            }
            val operation =
                SharedOperationContext.create(
                    fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed),
                    resolverObserver = observer,
                )
            val result = operation.resolve(fixture.schemas.fragmentFrom("fragment F on Query { first second }").subselections)

            assertEquals(8, result.getCell(key(operation.world, "Query", "first")).get())
            assertEquals(11, result.getCell(key(operation.world, "Query", "second")).get())
            assertEquals(
                listOf("base", "base", "first", "item", "middle", "second"),
                calls.map { it.field.name }.sorted(),
            )
            assertEquals(6, calls.toSet().size)
        }
    }

    @Test
    fun `variable templates distinguish object and associated Query occurrences`() {
        for (precomputed in listOf(false, true)) {
            val consumerCalls = mutableListOf<Int>()
            val middleCalls = mutableListOf<Int>()
            val fixture =
                queryVariableClosureFixture(
                    consumerProvider = { arguments ->
                        val seed = arguments.fieldValues.getValue("seed") as Int
                        consumerCalls += seed
                        mapOf("value" to seed + 1)
                    },
                    middleProvider = { arguments ->
                        val value = arguments.fieldValues.getValue("value") as Int
                        middleCalls += value
                        mapOf("value" to value * 2)
                    },
                )
            assertTrue(consumerCalls.isEmpty())
            assertTrue(middleCalls.isEmpty())
            val world = fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed)
            val result =
                SharedOperationContext.create(world)
                    .resolve(fixture.schemas.fragmentFrom("fragment F on Query { consumer(seed: 4) reuse }").subselections)

            assertEquals(10, result.getCell(key(world, "Query", "consumer", mapOf("seed" to 4))).get())
            assertEquals(10, result.getCell(key(world, "Query", "reuse")).get())
            assertEquals(listOf(4, 4), consumerCalls)
            assertEquals(listOf(5), middleCalls)
        }
    }

    @Test
    fun `variable templates coalesce grounded dependencies before invoking their providers`() {
        for (precomputed in listOf(false, true)) {
            val consumerCalls = mutableListOf<Int>()
            val middleCalls = mutableListOf<Int>()
            val fixture =
                queryVariableClosureFixture(
                    consumerProvider = { arguments ->
                        consumerCalls += arguments.fieldValues.getValue("seed") as Int
                        mapOf("value" to 7)
                    },
                    middleProvider = { arguments ->
                        val value = arguments.fieldValues.getValue("value") as Int
                        middleCalls += value
                        mapOf("value" to value * 2)
                    },
                )
            val world = fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed)
            val result =
                SharedOperationContext.create(world)
                    .resolve(
                        fixture.schemas.fragmentFrom(
                            "fragment F on Query { first: consumer(seed: 4) same: consumer(seed: 4) second: consumer(seed: 5) }",
                        ).subselections,
                    )

            assertEquals(14, result.getCell(key(world, "Query", "consumer", mapOf("seed" to 4))).get())
            assertEquals(14, result.getCell(key(world, "Query", "consumer", mapOf("seed" to 5))).get())
            assertEquals(listOf(4, 5), consumerCalls.sorted())
            assertEquals(listOf(7), middleCalls)
        }
    }

    @Test
    fun `hybrid templates preserve static siblings and split a partially variable bearing subtree`() {
        val fixture = mixedQueryClosureFixture { error("registry construction must not invoke providers") }
        val template = requireNotNull(fixture.resolverRegistry.precomputedConstructionDemand(fixture.schema.requireObjectField("Query", "consumer"))).queryDemand
        val bar = fixture.schema.requireType("Bar") as ViaductSchema.Object

        assertTrue(template.reusableDemand.usedVariables().isEmpty())
        assertEquals(setOf("foo", "bar", "baz"), template.reusableDemand.fieldNames())
        assertEquals(setOf("fixed"), template.reusableDemand[key(fixture.assumptions, "Query", "bar")].subselections.merge(bar).fieldNames())
        assertEquals(setOf("bar"), template.templatedDemand.fieldNames())
        assertEquals(setOf("f1"), template.templatedDemand[key(fixture.assumptions, "Query", "bar")].subselections.merge(bar).fieldNames())
        assertEquals(setOf(Arguments.Variable.of(fixture.schema.requireObjectField("Query", "consumer"), "value")), template.templatedDemand.usedVariables())
        assertEquals(template.reusableDemand.groundKeys(), template.dependencies.keys)
    }

    @Test
    fun `hybrid templates keep conditional dependencies and descendants in residual demand`() {
        val fixture = TestWorld.fromSDL(
            selectiveResolvers = false,
            schemaSDL = "type Query { consumer(flag: Boolean!): Int stable: Int middle: Int base: Int branch: Branch } type Branch { fixed: Int conditional: Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                mapOf(
                    schema.requireObjectField("Query", "consumer") to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schemas.fragmentFrom(
                            "fragment Input on Query { stable middle @include(if: ${'$'}flag) branch { fixed conditional @skip(if: ${'$'}flag) } }",
                        ),
                    ) { _, _, _ -> 0 },
                    schema.requireObjectField("Query", "middle") to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schemas.fragmentFrom("fragment Input on Query { base }"),
                    ) { _, query, _ -> query.selectionValues().getValue("base") },
                )
            },
            variableProviders = { schemas ->
                val consumer = schemas.loweredSchema.requireObjectField("Query", "consumer")
                mapOf(Arguments.Variable.of(consumer, "flag") to schemas.loweredSchema.fromArgument(consumer, "flag"))
            },
        )
        val consumer = fixture.schema.requireObjectField("Query", "consumer")
        val template = requireNotNull(fixture.resolverRegistry.precomputedConstructionDemand(consumer)).queryDemand
        val branchType = fixture.schema.requireType("Branch") as ViaductSchema.Object
        val branchKey = key(fixture.assumptions, "Query", "branch")
        val middleKey = key(fixture.assumptions, "Query", "middle")
        val variable = Arguments.Variable.of(consumer, "flag")

        assertEquals(setOf("stable", "branch"), template.reusableDemand.fieldNames())
        assertTrue(template.reusableDemand.usedVariables().isEmpty())
        assertEquals(setOf("fixed"), template.reusableDemand[branchKey].subselections.merge(branchType).fieldNames())
        assertEquals(setOf("middle", "branch"), template.templatedDemand.fieldNames())
        assertEquals(setOf(variable), template.templatedDemand[middleKey].inclusionCondition.usedVariables())
        val conditionalChildren = template.templatedDemand[branchKey].subselections.merge(branchType)
        assertEquals(setOf("conditional"), conditionalChildren.fieldNames())
        assertEquals(setOf(variable), conditionalChildren[key(fixture.assumptions, "Branch", "conditional")].inclusionCondition.usedVariables())
        assertEquals(template.reusableDemand.groundKeys(), template.dependencies.keys)
    }

    @Test
    fun `hybrid closure combines static and dynamic subselections before sharing Query production`() {
        for (precomputed in listOf(false, true)) {
            val providers = mutableListOf<Int>()
            val calls = mutableListOf<ObjectEngineResult.GroundKey>()
            val fixture = mixedQueryClosureFixture { arguments ->
                val value = arguments.fieldValues.getValue("seed") as Int
                providers += value
                mapOf("value" to value)
            }
            assertTrue(providers.isEmpty())
            val world = fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed)
            val observer = object : ResolverObserver {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    calls += ObjectEngineResult.GroundKey.of(observation.field, observation.arguments)
                }
            }
            val result = SharedOperationContext.create(world, resolverObserver = observer)
                .resolve(fixture.schemas.fragmentFrom("fragment F on Query { first: consumer(seed: 4) second: consumer(seed: 5) }").subselections)

            assertEquals(23, result.getCell(key(world, "Query", "consumer", mapOf("seed" to 4))).get())
            assertEquals(25, result.getCell(key(world, "Query", "consumer", mapOf("seed" to 5))).get())
            assertEquals(listOf(4, 5), providers.sorted())
            assertEquals(listOf("bar", "base", "baz", "consumer", "consumer", "f1", "f1", "foo"), calls.map { it.field.name }.sorted())
            assertEquals(setOf(4, 5), calls.filter { it.field.name == "f1" }.map { (it.arguments as Arguments.Resolved).fieldValues.getValue("arg") }.toSet())
        }
    }

    @Test
    fun `failed nested variable bindings retain static siblings and suppress variable bearing resolution`() {
        for (precomputed in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val fixture = mixedQueryClosureFixture { error("provider failed") }
            val world = fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed)
            val observer = object : ResolverObserver {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    calls += observation.field.name
                }
            }
            val result = SharedOperationContext.create(world, resolverObserver = observer)
                .resolve(fixture.schemas.fragmentFrom("fragment F on Query { consumer(seed: 4) }").subselections)

            assertIs<ErrorEngineResult>(result.getCell(key(world, "Query", "consumer", mapOf("seed" to 4))).get())
            assertEquals(listOf("bar", "base", "baz", "foo"), calls.sorted())
        }
    }

    @Test
    fun `hybrid closure binds argument distinct occurrences of one dependency separately`() {
        for (precomputed in listOf(false, true)) {
            val middleCalls = mutableListOf<Int>()
            val fixture =
                queryVariableClosureFixture(
                    consumerProvider = { arguments -> mapOf("value" to (arguments.fieldValues.getValue("seed") as Int) + 1) },
                    middleProvider = { arguments ->
                        val value = arguments.fieldValues.getValue("value") as Int
                        middleCalls += value
                        mapOf("value" to value * 2)
                    },
                    consumerQuery = "middle(value: ${'$'}value) fixed: middle(value: 11)",
                )
            val world = fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed)
            val template = requireNotNull(fixture.resolverRegistry.precomputedConstructionDemand(world.schema.requireObjectField("Query", "consumer"))).queryDemand
            assertEquals(setOf(key(world, "Query", "middle", mapOf("value" to 11))), template.reusableDemand.groundKeys())
            assertEquals(setOf(key(world, "Query", "middle", mapOf("value" to 11))), template.dependencies.keys)
            val result = SharedOperationContext.create(world)
                .resolve(fixture.schemas.fragmentFrom("fragment F on Query { consumer(seed: 4) }").subselections)

            assertEquals(10, result.getCell(key(world, "Query", "consumer", mapOf("seed" to 4))).get())
            assertEquals(listOf(5, 11), middleCalls.sorted())
        }
    }

    @Test
    fun `failed variable bindings suppress dependency providers without rejecting the registry`() {
        for (precomputed in listOf(false, true)) {
            val middleCalls = mutableListOf<Int>()
            val echoCalls = mutableListOf<Int>()
            val fixture =
                queryVariableClosureFixture(
                    consumerProvider = { error("provider failed") },
                    middleProvider = { arguments ->
                        middleCalls += arguments.fieldValues.getValue("value") as Int
                        mapOf("value" to 1)
                    },
                    middleQuery = "echo(value: ${'$'}value) fixed: echo(value: 11)",
                )
            val world = fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed)
            val observer = object : ResolverObserver {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    if (observation.field.name == "echo") echoCalls += observation.arguments.fieldValues.getValue("value") as Int
                }
            }
            val result =
                SharedOperationContext.create(world, resolverObserver = observer)
                    .resolve(fixture.schemas.fragmentFrom("fragment F on Query { consumer(seed: 4) echo(value: 8) }").subselections)

            assertIs<ErrorEngineResult>(result.getCell(key(world, "Query", "consumer", mapOf("seed" to 4))).get())
            assertEquals(8, result.getCell(key(world, "Query", "echo", mapOf("value" to 8))).get())
            assertTrue(middleCalls.isEmpty())
            assertEquals(listOf(8), echoCalls)
        }
    }

    @Test
    fun `empty object closure produces no Query rooted demand`() {
        val fixture = queryClosureFixture()
        val closed =
            fixture.closeOrchestrationDemand(
                OrchestrationConstructionDemand.checkedObject(
                    fixture.schemas.fragmentFrom("fragment F on Query { leaf }").subselections,
                ),
            )

        assertEquals(setOf("leaf"), closed.objectRooted.checked.fieldNames())
        assertTrue(closed.objectRooted.unchecked.isEmpty())
        assertTrue(closed.queryRooted.checked.isEmpty())
        assertTrue(closed.queryRooted.unchecked.isEmpty())
    }

    @Test
    fun `object resolver Query demand seeds one transitively closed Query component`() {
        val fixture = queryClosureFixture()
        val closed =
            fixture.closeOrchestrationDemand(
                OrchestrationConstructionDemand.checkedObject(
                    fixture.schemas.fragmentFrom("fragment F on Query { entry }").subselections,
                ),
            )

        assertEquals(
            setOf("entry", "objectOnly", "shared"),
            closed.objectRooted.checked.fieldNames(),
        )
        assertEquals(
            setOf("queryOnly", "shared", "transitive"),
            closed.queryRooted.checked.fieldNames(),
        )
        assertTrue(closed.objectRooted.unchecked.isEmpty())
        assertTrue(closed.queryRooted.unchecked.isEmpty())
    }

    @Test
    fun `overlapping Query inputs coalesce one exact key`() {
        val fixture = queryClosureFixture()
        val closed =
            fixture.closeOrchestrationDemand(
                OrchestrationConstructionDemand.checkedObject(
                    fixture.schemas.fragmentFrom("fragment F on Query { entry }").subselections,
                ),
            )

        assertEquals(3, closed.queryRooted.checked.size)
        assertEquals(
            1,
            closed.queryRooted.checked.groundKeys().count { key -> key.field.name == "shared" },
        )
    }

    @Test
    fun `checked and unchecked owners retain provenance while resolver inputs are checked`() {
        val fixture = queryClosureFixture()
        val ownerSelections =
            fixture.schemas.fragmentFrom("fragment F on Query { checkedOwner }").subselections
        val rawOwnerSelections =
            fixture.schemas.fragmentFrom("fragment F on Query { rawOwner }").subselections
        val closed =
            fixture.closeOrchestrationDemand(
                OrchestrationConstructionDemand(
                    objectRooted = Demand(ownerSelections, rawOwnerSelections, typeCheckDemanded = false),
                    queryRooted = Demand(rawOwnerSelections, rawOwnerSelections, typeCheckDemanded = false),
                ),
            )

        assertEquals(
            setOf("checkedOwner", "checkedDependency", "rawDependency"),
            closed.objectRooted.checked.fieldNames(),
        )
        assertEquals(setOf("rawOwner"), closed.objectRooted.unchecked.fieldNames())
        assertEquals(
            setOf("rawOwner", "rawDependency"),
            closed.queryRooted.checked.fieldNames(),
        )
        assertEquals(setOf("rawOwner"), closed.queryRooted.unchecked.fieldNames())
    }

    @Test
    fun `object rooted parent lifting routes activated resolver inputs across root axes`() {
        val fixture = parentClosureFixture()
        val childResult =
            fixture.schemas.fragmentFrom("fragment F on Root { child { result } }").subselections
        for (precomputed in listOf(false, true)) {
            val closed =
                fixture.closeOrchestrationDemand(
                    OrchestrationConstructionDemand.checkedObject(childResult),
                    objectTypeName = "Root",
                    usePrecomputedConstructionDemand = precomputed,
                )

            assertEquals(
                setOf("child", "lifted", "local"),
                closed.objectRooted.checked.fieldNames(),
            )
            assertTrue(closed.objectRooted.unchecked.isEmpty())
            assertEquals(
                setOf("queryInput", "transitive"),
                closed.queryRooted.checked.fieldNames(),
            )
            assertTrue(closed.queryRooted.unchecked.isEmpty())
        }
    }

    @Test
    fun `query rooted parent lifting remains below the Query root`() {
        val fixture = parentClosureFixture()
        val schema = fixture.assumptions.schema
        val childResult =
            fixture.schemas.fragmentFrom("fragment F on Query { root { child { result } } }").subselections
        for (precomputed in listOf(false, true)) {
            val closed =
                fixture.closeOrchestrationDemand(
                    OrchestrationConstructionDemand(
                        objectRooted = Demand.EMPTY,
                        queryRooted = Demand.unchecked(childResult),
                    ),
                    usePrecomputedConstructionDemand = precomputed,
                )

            assertTrue(closed.objectRooted.checked.isEmpty())
            assertTrue(closed.objectRooted.unchecked.isEmpty())
            assertTrue(closed.queryRooted.checked.isEmpty())
            assertFalse(closed.queryRooted.typeCheckDemanded)
            assertEquals(setOf("root"), closed.queryRooted.unchecked.fieldNames())
            val root = closed.queryRooted.unchecked.byGroundKey().values.single()
            assertEquals(
                setOf("child", "lifted"),
                root.subselections.merge(schema.requireObjectField("Query", "root").type.baseTypeDef as viaduct.graphql.schema.ViaductSchema.Object).fieldNames(),
            )
        }
    }

    @Test
    fun `closure and order keep accumulators local to each invocation`() {
        val worldFixture = fixture()
        val world = worldFixture.assumptions
        val schema = world.schema
        val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())
        val occurrence = OEROccurrence(root, emptyList(), root)
        val operation = SharedOperationContext.create(world)
        val source = schema.objectOf("Query")
        val ordering = SiblingDependencyLogic(operation, occurrence)
        val a = key(world, "Query", "a")
        val b = key(world, "Query", "b")
        val leaf = key(world, "Query", "leaf")
        val demand = worldFixture.schemas.fragmentFrom("fragment F on Query { a }").subselections

        repeat(2) {
            assertEquals(
                setOf(a, b, leaf),
                source.closeObjectDemand(operation, occurrence, demand)
                    .groundKeys(),
            )
            assertEquals(listOf(leaf, b, a), ordering.order(linkedSetOf(a, b, leaf)))
            assertEquals(
                emptySet(),
                source.closeObjectDemand(operation, occurrence, selectionForestOf())
                    .groundKeys(),
            )
            assertEquals(emptyList(), ordering.order(emptySet()))
            assertEquals(listOf(leaf), ordering.order(setOf(leaf)))
        }
    }

    @Test
    fun `closure preserves exact root list position and consumer key for argument variables`() {
        val worldFixture = fixture()
        val world = worldFixture.assumptions
        val schema = world.schema
        val operation = SharedOperationContext.create(world)
        val boxType = schema.requireObjectField("Box", "consumer").containingDef
        val boxes = key(world, "Query", "boxes")
        val consumer = key(world, "Box", "consumer", mapOf("seed" to 7))
        val sibling = key(world, "Box", "sibling", mapOf("value" to 7))
        val demand =
            worldFixture.schemas.fragmentFrom("fragment F on Box { consumer(seed: 7) }").subselections

        repeat(2) {
            val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())
            repeat(2) { index ->
                val path = listOf(boxes, ListEngineResult.Index.of(index))
                val target = ObjectEngineResult.of(boxType, emptyMap())
                val occurrence = OEROccurrence(root, path, target)
                val closedObjectDemand =
                    schema.objectOf("Box").closeObjectDemand(
                        operation,
                        occurrence,
                        demand,
                    )
                assertEquals(setOf(consumer, sibling), closedObjectDemand.groundKeys())
                val variable =
                    Arguments.Variable
                        .of(consumer.field, "seed")
                        .instantiate(ResolverOccurrenceId.at(root, path + consumer))
                assertEquals(
                    VariableBinding.of(7),
                    operation.variableBindings.getBinding(requireNotNull(variable.instanceId)),
                )
                assertEquals(
                    listOf(sibling, consumer),
                    SiblingDependencyLogic(operation, occurrence)
                        .order(linkedSetOf(consumer, sibling)),
                )
            }
        }
    }

    @Test
    fun `source supplied active fields do not expand standard resolver fragments`() {
        val worldFixture = fixture()
        val world = worldFixture.assumptions
        val schema = world.schema
        val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())
        val source = schema.objectOf("Query") { "a" setTo 99 }
        val operation = SharedOperationContext.create(world)
        val occurrence = OEROccurrence(root, emptyList(), root)
        val demand = worldFixture.schemas.fragmentFrom("fragment F on Query { a }").subselections

        assertEquals(
            setOf(key(world, "Query", "a")),
            source.closeObjectDemand(operation, occurrence, demand)
                .groundKeys(),
        )
    }

    @Test
    fun `passive argument-bearing source validation retains error argument precedence`() {
        val worldFixture = fixture()
        val world = worldFixture.assumptions
        val schema = world.schema
        val field = schema.requireObjectField("Box", "consumer")
        val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())
        val occurrence =
            OEROccurrence(
                root,
                listOf(key(world, "Query", "boxes"), ListEngineResult.Index.of(0)),
                ObjectEngineResult.of(field.containingDef, emptyMap()),
            )
        val source =
            engineObjectDataOf(
                field.containingDef,
                listOf(EngineObjectDataEntry.of("consumer", field, 1)),
            )
        val operation = SharedOperationContext.create(world)
        val errored = ObjectEngineResult.GroundKey.of(field, Arguments.Error)
        val errorDemand =
            selectionForestOf(
                Selection.of(errored, setOf(field.containingDef), selectionForestOf()),
            )
        assertEquals(
            setOf(errored),
            source.closeObjectDemand(operation, occurrence, errorDemand)
                .groundKeys(),
        )

        val ordinaryDemand =
            worldFixture.schemas.fragmentFrom("fragment F on Box { consumer(seed: 7) }").subselections
        val failure =
            assertFailsWith<IllegalArgumentException> {
                source.closeObjectDemand(operation, occurrence, ordinaryDemand)
            }
        assertEquals(
            "Resolver output must not supply argument-bearing field Box/consumer",
            failure.message,
        )
    }

    @Test
    fun `error arguments bypass missing resolver validation while ordinary demand rejects it`() {
        val worldFixture = fixture()
        val world = worldFixture.assumptions
        val schema = world.schema
        val field = schema.requireObjectField("Box", "missing")
        val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())
        val occurrence =
            OEROccurrence(
                root,
                listOf(key(world, "Query", "boxes"), ListEngineResult.Index.of(0)),
                ObjectEngineResult.of(field.containingDef, emptyMap()),
            )
        val operation = SharedOperationContext.create(world)
        val normal = key(world, "Box", "missing", mapOf("value" to 1))
        val errored = ObjectEngineResult.GroundKey.of(field, Arguments.Error)
        val ordering = SiblingDependencyLogic(operation, occurrence)
        assertEquals(listOf(errored), ordering.order(setOf(errored)))
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ordering.order(setOf(normal))
            }
        assertEquals(
            "Demanded field Box/missing is absent from its source and has no registered resolver",
            failure.message,
        )
        val demand =
            selectionForestOf(
                Selection.of(errored, setOf(field.containingDef), selectionForestOf()),
            )
        assertEquals(
            setOf(errored),
            schema.objectOf("Box")
                .closeObjectDemand(operation, occurrence, demand)
                .groundKeys(),
        )
    }

    @Test
    fun `reclosing a bound occurrence still rejects duplicate variable completion`() {
        val worldFixture = fixture()
        val world = worldFixture.assumptions
        val schema = world.schema
        val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())
        val boxType = schema.requireObjectField("Box", "consumer").containingDef
        val occurrence =
            OEROccurrence(
                root,
                listOf(key(world, "Query", "boxes"), ListEngineResult.Index.of(0)),
                ObjectEngineResult.of(boxType, emptyMap()),
            )
        val operation = SharedOperationContext.create(world)
        val source = schema.objectOf("Box")
        val demand =
            worldFixture.schemas.fragmentFrom("fragment F on Box { consumer(seed: 7) }").subselections
        source.closeObjectDemand(operation, occurrence, demand)

        assertFailsWith<IllegalStateException> {
            source.closeObjectDemand(operation, occurrence, demand)
        }
    }

    private fun key(
        world: viaduct.engine.runtime2.model.registry.Assumptions,
        type: String,
        name: String,
        arguments: Map<String, Any?> = emptyMap(),
    ): ObjectEngineResult.GroundKey =
        ObjectEngineResult.GroundKey.of(
            world.schema.requireObjectField(type, name),
            arguments,
        )

    private fun fixture(): TestWorld =
        TestWorld.fromDSL(
            schemaSDL =
                """
                extend type Query {
                  a: Int @resolver(of: "b", result: "sum(b)")
                  b: Int @resolver(of: "leaf", result: "sum(leaf)")
                  leaf: Int @resolver(result: 1)
                  boxes: [Box] @resolver(result: [])
                }
                type Box {
                  consumer(seed: Int!): Int @resolver(of: "sibling(value: ${'$'}seed)", result: "sum(sibling)")
                  sibling(value: Int!): Int @resolver(result: "sum(${'$'}value)")
                  missing(value: Int!): Int
                }
                """.trimIndent(),
        )

    private fun mixedQueryClosureFixture(provider: VariablesProviderFunction): TestWorld =
        TestWorld.fromSDL(
            selectiveResolvers = false,
            schemaSDL = """
                type Query { consumer(seed: Int!): Int! foo: Leaf! bar: Bar! baz: Leaf! base(value: Int!): Int! }
                type Leaf { value: Int! }
                type Bar { fixed: Int! f1(arg: Int!): Leaf! }
            """.trimIndent(),
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                val empty = schema.emptyFragmentOf("Query")
                mapOf(
                    schema.requireObjectField("Query", "consumer") to
                        fieldResolverOf(
                            empty,
                            schemas.fragmentFrom("fragment F on Query { foo { value } bar { fixed f1(arg: ${'$'}value) { value } } baz { value } }"),
                        ) { _, query, _ ->
                            val values = query.selectionValues()
                            val foo = (values.getValue("foo") as EngineObjectData.Sync).selectionValues()
                            val bar = (values.getValue("bar") as EngineObjectData.Sync).selectionValues()
                            val baz = (values.getValue("baz") as EngineObjectData.Sync).selectionValues()
                            val f1 = (bar.getValue("f1") as EngineObjectData.Sync).selectionValues()
                            (foo.getValue("value") as Int) + (bar.getValue("fixed") as Int) + (baz.getValue("value") as Int) + (f1.getValue("value") as Int)
                        }.withVariablesProvider(setOf("value"), provider),
                    schema.requireObjectField("Query", "foo") to
                        fieldResolverOf(empty, schemas.fragmentFrom("fragment F on Query { base(value: 7) }")) { _, query, _ ->
                            schema.objectOf("Leaf") { "value" setTo query.selectionValues().getValue("base") }
                        },
                    schema.requireObjectField("Query", "bar") to
                        fieldResolverOf(empty) { _, _ -> schema.objectOf("Bar") { "fixed" setTo 5 } },
                    schema.requireObjectField("Query", "baz") to
                        fieldResolverOf(empty) { _, _ -> schema.objectOf("Leaf") { "value" setTo 3 } },
                    schema.requireObjectField("Query", "base") to
                        fieldResolverOf(empty) { _, arguments -> arguments.fieldValues.getValue("value") },
                    schema.requireObjectField("Bar", "f1") to
                        fieldResolverOf(schema.emptyFragmentOf("Bar")) { _, arguments ->
                            schema.objectOf("Leaf") { "value" setTo (arguments.fieldValues.getValue("arg") as Int) * 2 }
                        },
                )
            },
        )

    private fun queryVariableClosureFixture(
        consumerProvider: VariablesProviderFunction,
        middleProvider: VariablesProviderFunction,
        consumerQuery: String = "middle(value: ${'$'}value)",
        middleQuery: String = "echo(value: ${'$'}value)",
    ): TestWorld =
        TestWorld.fromSDL(
            selectiveResolvers = false,
            schemaSDL = "type Query { consumer(seed: Int!): Int! middle(value: Int!): Int! echo(value: Int!): Int! reuse: Int! }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                val empty = schema.emptyFragmentOf("Query")
                mapOf(
                    schema.requireObjectField("Query", "consumer") to
                        fieldResolverOf(empty, schemas.fragmentFrom("fragment F on Query { $consumerQuery }")) { _, query, _ ->
                            query.selectionValues().getValue("middle")
                        }.withVariablesProvider(setOf("value"), consumerProvider),
                    schema.requireObjectField("Query", "middle") to
                        fieldResolverOf(empty, schemas.fragmentFrom("fragment F on Query { $middleQuery }")) { _, query, _ ->
                            query.selectionValues().getValue("echo")
                        }.withVariablesProvider(setOf("value"), middleProvider),
                    schema.requireObjectField("Query", "echo") to
                        fieldResolverOf(empty) { _, arguments -> arguments.fieldValues.getValue("value") },
                    schema.requireObjectField("Query", "reuse") to
                        fieldResolverOf(empty, schemas.fragmentFrom("fragment F on Query { consumer(seed: 4) }")) { _, query, _ ->
                            query.selectionValues().getValue("consumer")
                        },
                )
            },
        )

    private fun precomputedClosureFixture(): TestWorld =
        TestWorld.fromSDL(
            selectiveResolvers = false,
            schemaSDL = """
                type Query { base(value: Int!): Int! middle: Int! first: Int! second: Int! item: Item! }
                type Item { left: Int! right: Int! }
            """.trimIndent(),
            fieldResolvers = { schema ->
                val empty = schema.loweredSchema.emptyFragmentOf("Query")
                mapOf(
                    schema.loweredSchema.requireObjectField("Query", "base") to
                        fieldResolverOf(empty) { _, arguments -> arguments.fieldValues.getValue("value") },
                    schema.loweredSchema.requireObjectField("Query", "item") to
                        fieldResolverOf(empty) { _, _ ->
                            schema.loweredSchema.objectOf("Item") {
                                "left" setTo 1
                                "right" setTo 2
                            }
                        },
                    schema.loweredSchema.requireObjectField("Query", "middle") to
                        fieldResolverOf(empty, schema.fragmentFrom("fragment F on Query { base(value: 7) }")) { _, queryValue, _ ->
                            queryValue.selectionValues().getValue("base")
                        },
                    schema.loweredSchema.requireObjectField("Query", "first") to
                        fieldResolverOf(empty, schema.fragmentFrom("fragment F on Query { middle item { left } }")) { _, queryValue, _ ->
                            val item = queryValue.selectionValues().getValue("item") as EngineObjectData.Sync
                            (queryValue.selectionValues().getValue("middle") as Int) + (item.selectionValues().getValue("left") as Int)
                        },
                    schema.loweredSchema.requireObjectField("Query", "second") to
                        fieldResolverOf(empty, schema.fragmentFrom("fragment F on Query { base(value: 9) item { right } }")) { _, queryValue, _ ->
                            val item = queryValue.selectionValues().getValue("item") as EngineObjectData.Sync
                            (queryValue.selectionValues().getValue("base") as Int) + (item.selectionValues().getValue("right") as Int)
                        },
                )
            },
        )

    private fun queryClosureFixture(): TestWorld =
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Query {
                  entry: Int!
                  objectOnly: Int!
                  queryOnly: Int!
                  shared: Int!
                  transitive: Int!
                  leaf: Int!
                  checkedOwner: Int!
                  checkedDependency: Int!
                  rawOwner: Int!
                  rawDependency: Int!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val empty = schema.loweredSchema.emptyFragmentOf("Query")
                val entry = schema.loweredSchema.requireObjectField("Query", "entry")
                val queryOnly = schema.loweredSchema.requireObjectField("Query", "queryOnly")
                val checkedOwner = schema.loweredSchema.requireObjectField("Query", "checkedOwner")
                val rawOwner = schema.loweredSchema.requireObjectField("Query", "rawOwner")
                listOf(
                    "entry",
                    "objectOnly",
                    "queryOnly",
                    "shared",
                    "transitive",
                    "leaf",
                    "checkedOwner",
                    "checkedDependency",
                    "rawOwner",
                    "rawDependency",
                ).associate { fieldName ->
                    val field = schema.loweredSchema.requireObjectField("Query", fieldName)
                    field to
                        when (field) {
                            entry ->
                                fieldResolverOf(
                                    objectFragment =
                                        schema.fragmentFrom(
                                            "fragment EntryObject on Query { objectOnly shared }",
                                        ),
                                    queryFragment =
                                        schema.fragmentFrom(
                                            "fragment EntryQuery on Query { queryOnly shared }",
                                        ),
                                ) { _, _, _ -> 1 }
                            queryOnly ->
                                fieldResolverOf(
                                    objectFragment =
                                        schema.fragmentFrom(
                                            "fragment QueryOnlyObject on Query { shared }",
                                        ),
                                    queryFragment =
                                        schema.fragmentFrom(
                                            "fragment QueryOnlyQuery on Query { transitive }",
                                        ),
                                ) { _, _, _ -> 1 }
                            checkedOwner ->
                                fieldResolverOf(
                                    schema.fragmentFrom(
                                        "fragment CheckedOwner on Query { checkedDependency }",
                                    ),
                                ) { _, _ -> 1 }
                            rawOwner ->
                                fieldResolverOf(
                                    schema.fragmentFrom(
                                        "fragment RawOwner on Query { rawDependency }",
                                    ),
                                ) { _, _ -> 1 }
                            else -> fieldResolverOf(empty) { _, _ -> 1 }
                        }
                }
            },
        )

    private fun parentClosureFixture(): TestWorld =
        TestWorld.fromSDL(
            schemaSDL =
                """
                directive @parent on FIELD_DEFINITION

                type Query { root: Root! queryInput: Int! transitive: Int! }

                type Root {
                  child: Child!
                  lifted: Int!
                  local: Int!
                }

                type Child {
                  parent: Root @parent
                  result: Int!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val emptyQuery = schema.loweredSchema.emptyFragmentOf("Query")
                val emptyRoot = schema.loweredSchema.emptyFragmentOf("Root")
                val child = schema.loweredSchema.requireObjectField("Root", "child")
                val lifted = schema.loweredSchema.requireObjectField("Root", "lifted")
                val local = schema.loweredSchema.requireObjectField("Root", "local")
                val queryInput = schema.loweredSchema.requireObjectField("Query", "queryInput")
                val transitive = schema.loweredSchema.requireObjectField("Query", "transitive")
                val result = schema.loweredSchema.requireObjectField("Child", "result")
                mapOf(
                    child to
                        fieldResolverOf(emptyRoot) { _, _ -> schema.loweredSchema.objectOf("Child") },
                    lifted to
                        fieldResolverOf(
                            objectFragment =
                                schema.fragmentFrom("fragment LiftedObject on Root { local }"),
                            queryFragment =
                                schema.fragmentFrom(
                                    "fragment LiftedQuery on Query { queryInput }",
                                ),
                        ) { _, _, _ -> 1 },
                    local to fieldResolverOf(emptyRoot) { _, _ -> 1 },
                    queryInput to
                        fieldResolverOf(
                            objectFragment = emptyQuery,
                            queryFragment =
                                schema.fragmentFrom(
                                    "fragment QueryInput on Query { transitive }",
                                ),
                        ) { _, _, _ -> 1 },
                    transitive to fieldResolverOf(emptyQuery) { _, _ -> 1 },
                    result to
                        fieldResolverOf(
                            schema.fragmentFrom(
                                "fragment ChildResult on Child { parent { lifted } }",
                            ),
                        ) { _, _ -> 1 },
                )
            },
        )

    private fun TestWorld.closeOrchestrationDemand(
        initialDemand: OrchestrationConstructionDemand<SelectionForest>,
        objectTypeName: String = "Query",
        usePrecomputedConstructionDemand: Boolean = false,
    ): OrchestrationConstructionDemand<ObjectSelectionForest> {
        val world = newAssumptions(usePrecomputedConstructionDemand = usePrecomputedConstructionDemand)
        val query = world.schema.requireQueryTypeDef()
        val source = world.schema.objectOf(objectTypeName)
        val objectRoot = ObjectEngineResult.of(source.schemaType, emptyMap())
        val queryRoot = ObjectEngineResult.of(query, emptyMap())
        return source.closeOrchestrationConstructionDemand(
            operation = SharedOperationContext.create(world),
            objectOccurrence = OEROccurrence(objectRoot, emptyList(), objectRoot),
            queryOccurrence = OEROccurrence(queryRoot, emptyList(), queryRoot),
            initialDemand = initialDemand,
        )
    }

    private fun EngineObjectData.Sync.closeObjectDemand(
        operation: SharedOperationContext<*>,
        objectOccurrence: OEROccurrence,
        initialDemand: SelectionForest,
    ): ObjectSelectionForest {
        val queryRoot =
            ObjectEngineResult.of(operation.world.schema.requireQueryTypeDef(), emptyMap())
        return closeOrchestrationConstructionDemand(
            operation = operation,
            objectOccurrence = objectOccurrence,
            queryOccurrence = OEROccurrence(queryRoot, emptyList(), queryRoot),
            initialDemand = OrchestrationConstructionDemand.checkedObject(initialDemand),
        ).objectRooted.values.merge(schemaType)
    }

    private fun ObjectSelectionForest.fieldNames(): Set<String> = keys().mapTo(linkedSetOf()) { key -> key.field.name }
}
