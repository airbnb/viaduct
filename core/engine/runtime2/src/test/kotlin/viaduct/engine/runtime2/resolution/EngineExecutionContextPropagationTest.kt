@file:Suppress("DEPRECATION")

package viaduct.engine.runtime2.resolution

import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.airbnb.resolverRegistryWithRequestDependencies
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.materializeCheckedValue
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.FieldResolverDefinition
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.selectiveFieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.OrchestrationConstructionDemand
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

class EngineExecutionContextPropagationTest : ResolutionDispatcherResource {
    @Test
    fun `shared successor caches preserve request-dependent type checker applicability`() {
        val denial = object : CheckerResult.Error {
            override val error = SecurityException("Item access denied")

            override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

            override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
        }
        for (precomputed in listOf(false, true)) {
            for (firstApplicable in listOf(false, true)) {
                val fixture = TestWorld.fromSDL("type Query { item: Item } type Item { value: Int permission: Int }")
                val schema = fixture.schema
                val item = schema.requireType("Item") as ViaductSchema.Object
                val query = schema.requireType("Query") as ViaductSchema.Object
                val itemField = schema.requireObjectField("Query", "item")
                val value = schema.requireObjectField("Item", "value")
                val applicable = engineExecutionContextStub()
                val inapplicable = engineExecutionContextStub()
                val checkerCalls = AtomicInteger()
                val supplied = ConcurrentLinkedQueue<Set<String>>()
                val registry = resolverRegistryWithRequestDependencies(
                    fixture.schemas,
                    nodeResolvers = emptyMap(),
                    fieldResolvers = mapOf(
                        itemField to selectiveFieldResolverOf(schema.emptyFragmentOf("Query")) { _, _, demand ->
                            supplied += demand.merge(item).keys().map { it.field.name }.toSet()
                            engineObjectDataOf(item, mapOf("value" to 42, "permission" to 7))
                        },
                    ),
                    typeCheckers = mapOf(
                        item to TypeCheckerResolver.of(
                            item,
                            query,
                            mapOf(
                                "input" to ResolverFragmentTemplates(
                                    fixture.schemas.fragmentFrom("fragment Input on Item { permission }").materializeSelections,
                                    materializeSelectionForestOf(),
                                ),
                            ),
                            isApplicableForExecution = { context, _, _ -> context === applicable },
                        ) { inputs, context ->
                            assertSame(applicable, context.engineExecutionContext)
                            assertEquals(7, inputs.getValue("input").objectValue.get("permission"))
                            checkerCalls.incrementAndGet()
                            denial
                        },
                    ),
                    variableProviders = emptyMap(),
                    fieldDependenciesEnabled = emptyMap(),
                )
                val world = Assumptions.of(schema, registry, usePrecomputedConstructionDemand = precomputed)
                val demand = fixture.schemas.fragmentFrom("fragment F on Query { item { value } }").subselections
                val cache = (registry as FixedSuccessorDemandCacheOwner).fixedSuccessorDemandCache
                val boundary = SuccessorBoundary(type = item, kind = SuccessorBoundaryKind.TYPE_CHECKER)
                var warmed: viaduct.engine.runtime2.model.SelectionForest? = null
                for (enabled in listOf(firstApplicable, !firstApplicable, firstApplicable)) {
                    checkerCalls.set(0)
                    supplied.clear()
                    val context = if (enabled) applicable else inapplicable
                    val result = SharedOperationContext.create(world, engineExecutionContext = context).resolveWithTestDispatcher(demand)
                    val itemCell = result.getCell(ObjectEngineResult.GroundKey.of(itemField, emptyMap()))
                    val itemResult = assertIs<ObjectEngineResult>(itemCell.value.get())

                    assertEquals(if (enabled) 1 else 0, checkerCalls.get())
                    assertSame(if (enabled) denial else null, itemResult.typeCheckerResult.get())
                    if (enabled) {
                        assertSame(denial.error, assertIs<ErrorEngineResult>(itemCell.materializeCheckedValue { true }).errorData.cause)
                    } else {
                        val visible = assertIs<ObjectEngineResult>(itemCell.materializeCheckedValue { true })
                        assertEquals(42, visible.getCell(ObjectEngineResult.GroundKey.of(value, emptyMap())).value.get())
                    }
                    // Producer lookahead retains checker inputs even when this request skips its checker.
                    assertEquals(setOf("value", "permission"), supplied.single())
                    val expansion = assertNotNull(cache.get(boundary))
                    if (warmed == null) warmed = expansion else assertSame(warmed, expansion)
                }
            }
        }
    }

    @Test
    fun `execution passes the active context when selecting object and Query dependencies`() {
        val fixture = TestWorld.fromSDL("type Query { item: Item lookup: Int } type Item { value: Int seed: Int }")
        val schema = fixture.schema
        val item = schema.requireType("Item") as ViaductSchema.Object
        val itemField = schema.requireObjectField("Query", "item")
        val value = schema.requireObjectField("Item", "value")
        val modern = engineExecutionContextStub()
        val classic = engineExecutionContextStub()
        val queryCalls = AtomicInteger()
        val supplied = ConcurrentLinkedQueue<Set<String>>()
        val registry = resolverRegistryWithRequestDependencies(
            fixture.schemas,
            nodeResolvers = emptyMap(),
            fieldResolvers = mapOf(
                itemField to selectiveFieldResolverOf(schema.emptyFragmentOf("Query")) { _, _, demand ->
                    supplied += demand.merge(item).keys().map { it.field.name }.toSet()
                    engineObjectDataOf(item, mapOf("seed" to 10))
                },
                schema.requireObjectField("Query", "lookup") to FieldResolverDefinition.of(schema.emptyFragmentOf("Query"), null) { _, _, _, _, context ->
                    assertSame(modern, context.engineExecutionContext)
                    queryCalls.incrementAndGet()
                    20
                },
                value to FieldResolverDefinition.of(
                    fixture.schemas.fragmentFrom("fragment F on Item { seed }"),
                    fixture.schemas.fragmentFrom("fragment F on Query { lookup }"),
                ) { input, queryInput, _, _, context ->
                    if (context.engineExecutionContext === modern) {
                        (input.get("seed") as Int) + (queryInput.get("lookup") as Int)
                    } else {
                        assertSame(classic, context.engineExecutionContext)
                        1
                    }
                },
            ),
            variableProviders = emptyMap(),
            fieldDependenciesEnabled = mapOf(
                value to { context ->
                    requireNotNull(context) { "Dependency selection requires the active execution context" }
                    check(context === modern || context === classic)
                    context === modern
                },
            ),
        )
        val demand = fixture.schemas.fragmentFrom("fragment F on Query { item { value } }").subselections
        for (precomputed in listOf(false, true)) {
            for (context in listOf(modern, classic, modern)) {
                queryCalls.set(0)
                supplied.clear()
                val operation = SharedOperationContext.create(
                    Assumptions.of(schema, registry, usePrecomputedConstructionDemand = precomputed),
                    engineExecutionContext = context,
                )
                val result = operation.resolveWithTestDispatcher(demand)
                val objectResult = assertIs<ObjectEngineResult>(result.getCell(ObjectEngineResult.GroundKey.of(itemField, emptyMap())).value.get())
                assertEquals(if (context === modern) 30 else 1, objectResult.getCell(ObjectEngineResult.GroundKey.of(value, emptyMap())).value.get())
                assertEquals(if (context === modern) 1 else 0, queryCalls.get())
                assertEquals(context === modern, "seed" in supplied.single())
            }
        }
    }

    @Test
    fun `child scopes reuse request-dependent expansions while independent operations do not`(): Unit =
        runTest {
            for (enabled in listOf(false, true)) {
                val fixture = TestWorld.fromSDL("type Query { item: Item } type Item { value: Int dependency: Int }")
                val schema = fixture.schema
                val item = schema.requireType("Item") as ViaductSchema.Object
                val itemField = schema.requireObjectField("Query", "item")
                val value = schema.requireObjectField("Item", "value")
                val selections = AtomicInteger()
                val registry = resolverRegistryWithRequestDependencies(
                    fixture.schemas,
                    nodeResolvers = emptyMap(),
                    fieldResolvers = mapOf(
                        itemField to selectiveFieldResolverOf(schema.emptyFragmentOf("Query")) { _, _, _ ->
                            engineObjectDataOf(item, mapOf("value" to 44, "dependency" to 7))
                        },
                        value to fieldResolverOf(fixture.schemas.fragmentFrom("fragment F on Item { dependency }")) { _, _ ->
                            error("The producer supplies value directly")
                        },
                    ),
                    variableProviders = emptyMap(),
                    fieldDependenciesEnabled = mapOf(
                        value to {
                            selections.incrementAndGet()
                            enabled
                        }
                    ),
                )
                val shared = SharedOperationContext.create(Assumptions.of(schema, registry), engineExecutionContext = engineExecutionContextStub())
                val operation = OperationContext.create(shared, this)
                val input = fixture.schemas.fragmentFrom("fragment F on Query { item { value } }").subselections

                fun resolve(scope: OperationContext) {
                    val result = scope.startResolve(input)
                    testScheduler.advanceUntilIdle()
                    val objectResult = result.getCell(ObjectEngineResult.GroundKey.of(itemField, emptyMap())).value.get() as ObjectEngineResult
                    assertEquals(44, objectResult.getCell(ObjectEngineResult.GroundKey.of(value, emptyMap())).value.get())
                }

                resolve(operation)
                val first = selections.get()
                org.junit.jupiter.api.Assertions.assertTrue(first > 0)
                resolve(operation.forChildScope(this))
                assertEquals(first, selections.get(), "A child scope must reuse the completed expansion")
                resolve(OperationContext.create(shared, this))
                assertEquals(first * 2, selections.get(), "An independent operation must select its own dependencies")
            }
        }

    @Test
    fun `request-dependent parent summaries are reused across calculations and isolated between operations`() {
        val fixture = TestWorld.fromSDL(
            """
            directive @parent on FIELD_DEFINITION
            type Query { item: Item }
            type Item { combined: Int child: Child dependency: Int }
            type Child { parent: Item @parent value: Int }
            """.trimIndent(),
        )
        val schema = fixture.schema
        val item = schema.requireType("Item") as ViaductSchema.Object
        val combined = schema.requireObjectField("Item", "combined")
        val value = schema.requireObjectField("Child", "value")
        val modern = engineExecutionContextStub()
        val classic = engineExecutionContextStub()
        val selections = AtomicInteger()
        val registry = resolverRegistryWithRequestDependencies(
            fixture.schemas,
            nodeResolvers = emptyMap(),
            fieldResolvers = mapOf(
                schema.requireObjectField("Query", "item") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> null },
                combined to fieldResolverOf(fixture.schemas.fragmentFrom("fragment F on Item { child { value } }")) { _, _ -> 1 },
                value to fieldResolverOf(fixture.schemas.fragmentFrom("fragment F on Child { parent { dependency } }")) { _, _ -> 1 },
            ),
            variableProviders = emptyMap(),
            fieldDependenciesEnabled = mapOf(
                value to { context ->
                    selections.incrementAndGet()
                    context === modern
                }
            ),
        )
        val world = Assumptions.of(schema, registry)
        val program = registry.resolver(combined).declaredDependencies.objectParentDemand
        val operations = listOf(modern, classic).map { it to OperationDemandCache() }
        val summaries = operations.map { (context, cache) ->
            val result = ParentDemandApplication(world, context, operationDemandCache = cache).evaluate(program)
            assertEquals(context === modern, result.localDemand.values.merge(item).keys().any { it.field.name == "dependency" })
            result
        }
        val first = selections.get()
        val executor = Executors.newFixedThreadPool(4)
        val barrier = CyclicBarrier(4)
        try {
            val tasks = (0 until 4).map { worker ->
                executor.submit {
                    barrier.await(10, TimeUnit.SECONDS)
                    val index = worker % operations.size
                    val (context, cache) = operations[index]
                    repeat(12) {
                        assertSame(summaries[index], ParentDemandApplication(world, context, operationDemandCache = cache).evaluate(program))
                    }
                }
            }
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(first, selections.get(), "Completed summaries must avoid repeating dependency selection")
        ParentDemandApplication(world, modern, operationDemandCache = OperationDemandCache()).evaluate(program)
        assertEquals(first + 1, selections.get(), "A separate operation must calculate its own summary")
    }

    @Test
    fun `concurrent request expansion caches preserve independent choices and warm reuse`() {
        val fixture = TestWorld.fromSDL("type Query { first: Int second: Int leaf: Int }")
        val schema = fixture.schema
        val query = schema.requireType("Query") as ViaductSchema.Object
        val firstField = schema.requireObjectField("Query", "first")
        val secondField = schema.requireObjectField("Query", "second")
        val modern = engineExecutionContextStub()
        val classic = engineExecutionContextStub()
        val selections = AtomicInteger()
        val registry = resolverRegistryWithRequestDependencies(
            fixture.schemas,
            nodeResolvers = emptyMap(),
            fieldResolvers = mapOf(
                firstField to fieldResolverOf(fixture.schemas.fragmentFrom("fragment F on Query { second }")) { _, _ -> 1 },
                secondField to fieldResolverOf(fixture.schemas.fragmentFrom("fragment F on Query { leaf }")) { _, _ -> 2 },
                schema.requireObjectField("Query", "leaf") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 3 },
            ),
            variableProviders = emptyMap(),
            fieldDependenciesEnabled = mapOf(
                secondField to { context ->
                    selections.incrementAndGet()
                    context === modern
                }
            ),
        )
        val world = Assumptions.of(schema, registry)
        val input = Demand.checked(fixture.schemas.fragmentFrom("fragment F on Query { first }").subselections)
        val operations = listOf(modern, classic).map { it to OperationDemandCache() }

        fun expand(index: Int) {
            val (context, cache) = operations[index]
            val result = input.successorDemandFromConstructionDemand(world, engineExecutionContext = context, operationDemandCache = cache)
            assertEquals(if (context === modern) setOf("first", "second", "leaf") else setOf("first", "second"), result.merge(query).keys().map { it.field.name }.toSet())
        }

        val executor = Executors.newFixedThreadPool(4)
        val barrier = CyclicBarrier(4)
        try {
            val tasks = (0 until 4).map { worker ->
                executor.submit {
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(12) { expand(worker % operations.size) }
                }
            }
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        val first = selections.get()
        operations.indices.forEach(::expand)
        assertEquals(first, selections.get(), "Warm ancestor expansions must reuse their selected descendants")
        val boundary = SuccessorBoundary(field = firstField, kind = SuccessorBoundaryKind.RESOLVER)
        org.junit.jupiter.api.Assertions.assertNull((registry as FixedSuccessorDemandCacheOwner).fixedSuccessorDemandCache.get(boundary))
        operations.forEach { (_, cache) -> assertNotNull(cache.successorDemands[boundary]) }
    }

    @Test
    fun `precomputed closure selects empty inputs for the inactive implementation`() {
        val fixture = TestWorld.fromSDL("type Query { item: Item } type Item { value: Int dependency: Int }")
        val schema = fixture.schema
        val item = schema.requireType("Item") as ViaductSchema.Object
        val query = schema.requireType("Query") as ViaductSchema.Object
        val value = schema.requireObjectField("Item", "value")
        val modern = engineExecutionContextStub()
        val classic = engineExecutionContextStub()
        val registry = resolverRegistryWithRequestDependencies(
            fixture.schemas,
            nodeResolvers = emptyMap(),
            fieldResolvers = mapOf(
                schema.requireObjectField("Query", "item") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> null },
                value to FieldResolverDefinition.of(fixture.schemas.fragmentFrom("fragment F on Item { dependency }"), null) { _, _, _, _, _ -> 1 },
            ),
            variableProviders = emptyMap(),
            fieldDependenciesEnabled = mapOf(value to { it !== classic }),
        )
        val declared = assertNotNull(registry.precomputedConstructionDemand(value, modern))
        val empty = assertNotNull(registry.precomputedConstructionDemand(value, classic))
        org.junit.jupiter.api.Assertions.assertNotSame(declared, empty)
        assertEquals(setOf("dependency"), declared.objectDemand.reusableDemand.keys().map { it.field.name }.toSet())
        assertEquals(true, empty.objectDemand.reusableDemand.isEmpty())
        assertEquals(true, empty.queryDemand.reusableDemand.isEmpty())
        assertSame(declared, registry.precomputedConstructionDemand(value, modern))
        assertSame(empty, registry.precomputedConstructionDemand(value, classic))
        val input = fixture.schemas.fragmentFrom("fragment F on Item { value }").subselections
        for (context in listOf(modern, classic, modern)) {
            val objectResult = ObjectEngineResult.of(item)
            val queryResult = ObjectEngineResult.of(query)
            val closed = engineObjectDataOf(item).closePrecomputedConstructionDemand(
                Assumptions.of(schema, registry),
                OEROccurrence(objectResult, emptyList(), objectResult),
                OEROccurrence(queryResult, emptyList(), queryResult),
                OrchestrationConstructionDemand(Demand.checked(input), Demand.EMPTY),
                context,
            )
            assertEquals(
                if (context === classic) setOf("value") else setOf("value", "dependency"),
                closed.objectRooted.constructionDemand.values.merge(item).keys().map { it.field.name }.toSet(),
            )
        }
    }

    @Test
    fun `independent templates compose parent requirements through a fixed resolver and checker`() {
        val fixture = TestWorld.fromSDL(
            """
            directive @parent on FIELD_DEFINITION
            type Query { item: Item }
            type Item { combined: Int child: Child leftInput: Int rightInput: Int }
            type Child { parent: Item @parent left: Int right: Int }
            """.trimIndent(),
        )
        val schema = fixture.schema
        val item = schema.requireType("Item") as ViaductSchema.Object
        val child = schema.requireType("Child") as ViaductSchema.Object
        val query = schema.requireType("Query") as ViaductSchema.Object
        val combined = schema.requireObjectField("Item", "combined")
        val choices = listOf(false to false, true to false, false to true, true to true).map { engineExecutionContextStub() to it }

        fun selection(context: EngineExecutionContext): Pair<Boolean, Boolean> = choices.first { it.first === context }.second
        val calls = ConcurrentLinkedQueue<String>()
        val supplied = ConcurrentLinkedQueue<Set<String>>()
        val requirements = fixture.schemas.fragmentFrom("fragment F on Item { child { left right } }")
        val fields = mutableMapOf<ViaductSchema.Field, FieldResolverDefinition>(
            schema.requireObjectField("Query", "item") to selectiveFieldResolverOf(schema.emptyFragmentOf("Query")) { _, _, demand ->
                supplied += demand.merge(item).keys().map { it.field.name }.toSet()
                engineObjectDataOf(item, mapOf("child" to engineObjectDataOf(child)))
            },
            combined to fieldResolverOf(requirements) { input, _ ->
                val value = input.get("child") as EngineObjectData.Sync
                (value.get("left") as Int) + (value.get("right") as Int)
            },
        )
        val predicates = mutableMapOf<ViaductSchema.ObjectField, (EngineExecutionContext?) -> Boolean>()
        for ((name, modernValue, classicValue) in listOf(Triple("left", 10, 1), Triple("right", 20, 2))) {
            fields[schema.requireObjectField("Item", "${name}Input")] = fieldResolverOf(schema.emptyFragmentOf("Item")) { _, _ ->
                calls += name
                modernValue
            }
            val field = schema.requireObjectField("Child", name)
            fields[field] = FieldResolverDefinition.of(
                fixture.schemas.fragmentFrom("fragment F on Child { parent { ${name}Input } }"),
                null,
            ) { input, _, _, _, context ->
                val selected = selection(requireNotNull(context.engineExecutionContext)).let { if (name == "left") it.first else it.second }
                if (selected) (input.get("parent") as EngineObjectData.Sync).get("${name}Input") else classicValue
            }
            predicates[field] = { context ->
                context == null || selection(context).let { if (name == "left") it.first else it.second }
            }
        }
        val registry = resolverRegistryWithRequestDependencies(
            fixture.schemas,
            emptyMap(),
            fields,
            variableProviders = emptyMap(),
            fieldDependenciesEnabled = predicates,
            fieldCheckers = mapOf(
                combined to FieldCheckerResolver.of(
                    combined,
                    query,
                    mapOf("input" to ResolverFragmentTemplates(requirements.materializeSelections, materializeSelectionForestOf())),
                ) { _, inputs, _ ->
                    val value = inputs.getValue("input").objectValue.get("child") as EngineObjectData.Sync
                    org.junit.jupiter.api.Assertions.assertNotNull(value.get("left"))
                    calls += "checker"
                    CheckerResult.Success
                }
            ),
        )
        val input = fixture.schemas.fragmentFrom("fragment F on Query { item { combined } }").subselections
        val cache = (registry as FixedSuccessorDemandCacheOwner).fixedSuccessorDemandCache
        val dynamicBoundary = SuccessorBoundary(field = combined, kind = SuccessorBoundaryKind.RESOLVER)
        val staticBoundary = SuccessorBoundary(field = schema.requireObjectField("Item", "leftInput"), kind = SuccessorBoundaryKind.RESOLVER)
        var sharedExpansion: viaduct.engine.runtime2.model.SelectionForest? = null
        assertEquals(false, cache.preparedInput(dynamicBoundary)!!.selectionIndependent)
        for (precomputed in listOf(false, true)) {
            for ((context, selected) in choices) {
                calls.clear()
                supplied.clear()
                val operation = SharedOperationContext.create(Assumptions.of(schema, registry, usePrecomputedConstructionDemand = precomputed), engineExecutionContext = context)
                val result = operation.resolveWithTestDispatcher(input)
                val objectResult = result.getCell(ObjectEngineResult.GroundKey.of(schema.requireObjectField("Query", "item"), emptyMap())).value.get() as ObjectEngineResult
                assertEquals(
                    (if (selected.first) 10 else 1) + (if (selected.second) 20 else 2),
                    objectResult.getCell(ObjectEngineResult.GroundKey.of(combined, emptyMap())).value.get(),
                    "precomputed=$precomputed, selected=$selected",
                )
                assertEquals(listOfNotNull("checker", "left".takeIf { selected.first }, "right".takeIf { selected.second }).sorted(), calls.sorted())
                assertEquals(selected.first, "leftInput" in supplied.single())
                assertEquals(selected.second, "rightInput" in supplied.single())
                org.junit.jupiter.api.Assertions.assertNull(cache.get(dynamicBoundary))
                if (selected.first) {
                    val expansion = assertNotNull(cache.get(staticBoundary))
                    if (sharedExpansion == null) sharedExpansion = expansion else assertSame(sharedExpansion, expansion)
                }
            }
        }
        val executor = Executors.newFixedThreadPool(choices.size)
        val barrier = CyclicBarrier(choices.size)
        try {
            val tasks = choices.map { (context, selected) ->
                executor.submit {
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(12) {
                        val lifted = Demand.checked(input).liftPrecomputedParentConstructionDemand(Assumptions.of(schema, registry), context)
                        val names = lifted.values.merge(query).byKey().values
                            .flatMap { it.subselections.merge(item).keys() }.map { it.field.name }.toSet()
                        assertEquals(selected.first, "leftInput" in names)
                        assertEquals(selected.second, "rightInput" in names)
                    }
                }
            }
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `child resolution scopes retain the request engine context`(): Unit =
        runTest {
            val world =
                TestWorld.fromSDL(
                    """
                    type Query {
                      value: Int
                    }
                    """.trimIndent(),
                )
            val engineContext = engineExecutionContextStub()
            val shared =
                SharedOperationContext.create(
                    world = world.assumptions,
                    engineExecutionContext = engineContext,
                )

            assertSame(engineContext, shared.engineExecutionContext)
            val operation = OperationContext.create(shared, this)
            assertSame(engineContext, operation.engineExecutionContext)
            assertSame(engineContext, operation.forChildScope(this).engineExecutionContext)
        }

    @Test
    fun `shared registry selects request dependencies despite warmed bootstrap expansions`() {
        for (parentInput in listOf(false, true)) {
            val fixture = TestWorld.fromSDL(
                """
                directive @parent on FIELD_DEFINITION
                type Query { item: Item }
                type Item { value: Int dependency: Int child: Child }
                type Child { parent: Item @parent value: Int }
                """.trimIndent(),
            )
            val schema = fixture.schema
            val item = schema.requireType("Item") as ViaductSchema.Object
            val child = schema.requireType("Child") as ViaductSchema.Object
            val value = schema.requireObjectField(if (parentInput) "Child" else "Item", "value")
            val dependency = schema.requireObjectField("Item", "dependency")
            val itemField = schema.requireObjectField("Query", "item")
            val modernContext = engineExecutionContextStub()
            val classicContext = engineExecutionContextStub()
            val calls = ConcurrentLinkedQueue<String>()
            val supplied = ConcurrentLinkedQueue<Set<String>>()
            val registry = resolverRegistryWithRequestDependencies(
                fixture.schemas,
                nodeResolvers = emptyMap(),
                fieldResolvers = mapOf(
                    itemField to selectiveFieldResolverOf(schema.emptyFragmentOf("Query")) { _, _, demand ->
                        calls += "item"
                        supplied += demand.merge(item).keys().map { it.field.name }.toSet()
                        engineObjectDataOf(item, if (parentInput) mapOf("child" to engineObjectDataOf(child)) else emptyMap())
                    },
                    dependency to fieldResolverOf(schema.emptyFragmentOf("Item")) { _, _ ->
                        calls += "dependency"
                        7
                    },
                    value to FieldResolverDefinition.of(
                        fixture.schemas.fragmentFrom(if (parentInput) "fragment F on Child { parent { dependency } }" else "fragment F on Item { dependency }"),
                        null,
                    ) { input, _, _, _, context ->
                        if (context.engineExecutionContext === classicContext) {
                            calls += "classic"
                            2
                        } else {
                            calls += "modern"
                            val owner = if (parentInput) input.get("parent") as EngineObjectData.Sync else input
                            (owner.get("dependency") as Int) + 1
                        }
                    },
                ),
                variableProviders = emptyMap(),
                fieldDependenciesEnabled = mapOf(value to { context -> context == null || context === modernContext }),
            )
            val input = fixture.schemas.fragmentFrom(if (parentInput) "fragment F on Query { item { child { value } } }" else "fragment F on Query { item { value } }").subselections
            val cache = (registry as FixedSuccessorDemandCacheOwner).fixedSuccessorDemandCache
            val boundary = SuccessorBoundary(field = dependency, kind = SuccessorBoundaryKind.RESOLVER)
            input.successorDemand(Assumptions.of(schema, registry), engineExecutionContext = null)
            val warmed = assertNotNull(cache.get(boundary))

            for (precomputed in listOf(false, true)) {
                var templateLookups = 0
                var cacheLookups = 0
                val observed = object : ResolverRegistry by registry, FixedSuccessorDemandCacheOwner {
                    override val fixedSuccessorDemandCache get() = cache.also { cacheLookups++ }

                    override fun precomputedConstructionDemand(
                        field: ViaductSchema.ObjectField,
                        context: EngineExecutionContext?
                    ) = registry.precomputedConstructionDemand(field, context).also { templateLookups++ }
                }
                val world = Assumptions.of(schema, observed, usePrecomputedConstructionDemand = precomputed)
                for (context in listOf(modernContext, classicContext, modernContext)) {
                    calls.clear()
                    supplied.clear()
                    val result = SharedOperationContext.create(world, engineExecutionContext = context).resolveWithTestDispatcher(input)
                    val itemValue = result.getCell(ObjectEngineResult.GroundKey.of(itemField, emptyMap())).value.get()
                    val itemResult =
                        assertIs<ObjectEngineResult>(
                            itemValue,
                            "parentInput=$parentInput, precomputed=$precomputed, modern=${context === modernContext}; error=${(itemValue as? ErrorEngineResult)?.errorData?.cause}"
                        )
                    val owner = if (parentInput) {
                        itemResult.getCell(ObjectEngineResult.GroundKey.of(schema.requireObjectField("Item", "child"), emptyMap())).value.get() as ObjectEngineResult
                    } else {
                        itemResult
                    }
                    val modern = context === modernContext
                    assertEquals(if (modern) 8 else 2, owner.getCell(ObjectEngineResult.GroundKey.of(value, emptyMap())).value.get())
                    assertEquals(if (modern) listOf("dependency", "item", "modern") else listOf("classic", "item"), calls.sorted())
                    assertEquals(modern, "dependency" in supplied.single())
                    assertSame(warmed, cache.get(boundary))
                }
                assertEquals(precomputed, templateLookups > 0)
                assertEquals(true, cacheLookups > 0)
            }
        }
    }

    @Test
    fun `root field references use the active Query dependencies from the shared registry`() {
        val fixture = TestWorld.fromSDL("type Query { value: Int target: Int dependency: Int }")
        val schema = fixture.schema
        val value = schema.requireObjectField("Query", "value")
        val target = schema.requireObjectField("Query", "target")
        val dependency = schema.requireObjectField("Query", "dependency")
        val modernContext = engineExecutionContextStub()
        val classicContext = engineExecutionContextStub()
        var dependencyCalls = 0
        val registry = resolverRegistryWithRequestDependencies(
            fixture.schemas,
            nodeResolvers = emptyMap(),
            fieldResolvers = mapOf(
                value to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                    RootFieldReferenceData.of(listOf(target), emptyMap())
                },
                dependency to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                    dependencyCalls++
                    8
                },
                target to FieldResolverDefinition.of(
                    schema.emptyFragmentOf("Query"),
                    fixture.schemas.fragmentFrom("fragment F on Query { dependency }"),
                ) { _, query, _, _, context ->
                    if (context.engineExecutionContext === classicContext) 2 else query.get("dependency")
                },
            ),
            variableProviders = emptyMap(),
            fieldDependenciesEnabled = mapOf(target to { context -> context !== classicContext }),
        )
        val preparedResolver = registry.resolver(target)
        val selections = fixture.schemas.fragmentFrom("fragment F on Query { value }").subselections

        for (context in listOf(modernContext, classicContext, modernContext)) {
            dependencyCalls = 0
            val result = SharedOperationContext.create(
                Assumptions.of(schema, registry),
                engineExecutionContext = context,
            ).resolveWithTestDispatcher(selections)

            val modern = context === modernContext
            assertEquals(if (modern) 8 else 2, result.getCell(ObjectEngineResult.GroundKey.of(value, emptyMap())).value.get())
            assertEquals(if (modern) 1 else 0, dependencyCalls)
            assertSame(preparedResolver, registry.resolver(target))
        }
    }

    private fun engineExecutionContextStub(): EngineExecutionContext =
        Proxy.newProxyInstance(
            EngineExecutionContext::class.java.classLoader,
            arrayOf(EngineExecutionContext::class.java),
        ) { _, method, _ ->
            error("Unexpected EngineExecutionContext.${method.name} invocation")
        } as EngineExecutionContext
}
