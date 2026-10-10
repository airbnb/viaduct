@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Disabled
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.nodeCount
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.OrchestrationConstructionDemand
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.ResolverObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.fieldResolverCycleTask
import viaduct.engine.runtime2.resolution.framework.valueCycleSlot
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

/** Structural probes translated from the pre-checker superlinear investigation. */
class SuperlinearGrowthTest : ResolutionDispatcherResource {
    @Test
    fun `runtime successor demand stays compact across a resolver diamond`() {
        val depth = 15
        val fields = (0..depth).joinToString("\n") { index ->
            val inputs = ((index + 1)..minOf(index + 2, depth)).joinToString(" ") { "field$it" }
            "field$index: Int @resolver(of: \"$inputs\", result: 1)"
        }
        val fixture = TestWorld.fromDSL(
            "extend type Query { root: Box @resolver(result: {}) } type Box { $fields }",
            fieldCheckers = { schema ->
                val checked = schema.loweredSchema.requireObjectField("Box", "field0")
                val pair =
                    ResolverFragmentTemplates(
                        schema.fragmentFrom("fragment CheckerInput on Box { field1 field2 }").materializeSelections,
                        materializeSelectionForestOf(),
                    )
                mapOf(
                    checked to
                        FieldCheckerResolver.of(
                            checked,
                            schema.loweredSchema.requireQueryTypeDef(),
                            mapOf("input" to pair),
                        ) { _, _, _ -> CheckerResult.Success },
                )
            },
            typeCheckers = { schema ->
                val type = schema.loweredSchema.requireObjectField("Query", "root").type.baseTypeDef as ViaductSchema.Object
                val pair =
                    ResolverFragmentTemplates(
                        schema.fragmentFrom("fragment TypeInput on Box { field0 }").materializeSelections,
                        materializeSelectionForestOf(),
                    )
                mapOf(
                    type to
                        TypeCheckerResolver.of(
                            type,
                            schema.loweredSchema.requireQueryTypeDef(),
                            mapOf("input" to pair),
                        ) { _, _ -> CheckerResult.Success },
                )
            },
        )
        val world = fixture.assumptions
        val applications = AtomicInteger()
        var suppliedSelections = 0
        val observer =
            object : ResolverObserver {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    applications.incrementAndGet()
                    if (observation.field.name == "root") suppliedSelections = requireNotNull(observation.suppliedDemand).size
                }
            }
        val result =
            SharedOperationContext
                .create(world, resolverObserver = observer)
                .resolveWithTestDispatcher(
                    fixture.schemas.fragmentFrom("fragment F on Query { root { field0 } }").subselections,
                )
        val rootKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "root"), emptyMap())
        val box = result.getCell(rootKey).value.get() as ObjectEngineResult
        assertEquals(depth + 1, box.keys.size)
        assertEquals(depth + 2, applications.get())
        assertTrue(
            suppliedSelections <= 2 * (depth + 1),
            "Resolver, field-checker, and type-checker diamonds supplied $suppliedSelections selections",
        )
    }

    @Test
    fun `successor demand stays compact across a resolver diamond`() {
        for (depth in listOf(5, 10, 15, 20)) {
            val fields = (0..depth).joinToString("\n") { index ->
                val inputs = ((index + 1)..minOf(index + 2, depth)).joinToString(" ") { "field$it" }
                "field$index: Int @resolver(of: \"$inputs\", result: 1)"
            }
            val fixture = TestWorld.fromDSL("extend type Query { $fields }")
            val world = fixture.assumptions
            val input = fixture.schemas.fragmentFrom("fragment F on Query { field0 }").subselections
            val demand = input.successorDemand(world)
            val distinct = demand.merge(world.schema.requireQueryTypeDef()).size
            assertEquals(depth + 1, distinct)
            assertTrue(demand.size <= 2 * distinct, "Successor demand expanded ${demand.size} selections for $distinct fields")
        }
    }

    @Test
    @Disabled("Nonempty parent demand still expands the resolver/checker dependency-path tree")
    fun `parent demand records one nonempty request across a resolver diamond`() {
        for (depth in listOf(5, 10, 15, 20)) {
            val fields = (0..depth).joinToString("\n") { index ->
                val inputs =
                    if (index == depth) {
                        "parent { seed }"
                    } else {
                        ((index + 1)..minOf(index + 2, depth)).joinToString(" ") { "field$it" }
                    }
                "field$index: Int @resolver(of: \"$inputs\", result: 1)"
            }
            val fixture =
                TestWorld.fromDSL(
                    """
                    extend type Query { root: Parent @resolver(result: {}) }
                    type Parent { seed: Int child: Child }
                    type Child { parent: Parent @parent $fields }
                    """.trimIndent(),
                    fieldCheckers = { schema ->
                        val pair =
                            ResolverFragmentTemplates(
                                schema.fragmentFrom("fragment CheckerInput on Child { parent { seed } }").materializeSelections,
                                materializeSelectionForestOf(),
                            )
                        (0..depth).associate { index ->
                            val field = schema.loweredSchema.requireObjectField("Child", "field$index")
                            field to
                                FieldCheckerResolver.of(
                                    field,
                                    schema.loweredSchema.requireQueryTypeDef(),
                                    mapOf("input" to pair),
                                ) { _, _, _ -> CheckerResult.Success }
                        }
                    },
                )
            val world = fixture.assumptions
            val input = fixture.schemas.fragmentFrom("fragment F on Query { root { child { field0 } } }").subselections
            val demand =
                Demand
                    .checked(input)
                    .liftParentConstructionDemand(world)
                    .values
                    .merge(world.schema.requireQueryTypeDef())
                    .single()
                    .subselections
            val distinct = demand.merge(world.schema.requireObjectField("Parent", "seed").containingDef).size
            assertEquals(1, distinct)
            assertTrue(
                demand.size <= 2 * (depth + 1),
                "Parent lifting expanded ${demand.size} seed selections across ${depth + 1} fields",
            )
        }
    }

    @Test
    fun `shared query closure keeps guarded alternatives compact`() {
        for (depth in listOf(3, 6, 9, 12)) {
            val fixture =
                TestWorld.fromSDL(
                    schemaSDL = "type Query { audit: Int " + (0..depth).joinToString(" ") { "field$it: Int" } + " }",
                    selectiveResolvers = true,
                    fieldResolvers = { schema ->
                        (0..depth).associate { index ->
                            val field = schema.loweredSchema.requireObjectField("Query", "field$index")
                            val empty = schema.loweredSchema.emptyFragmentOf("Query")
                            val resolver =
                                fieldResolverOf(
                                    objectFragment = empty,
                                    queryFragment =
                                        if (index == depth) {
                                            empty
                                        } else {
                                            schema.fragmentFrom(
                                                "fragment F on Query { left: field${index + 1} @include(if: ${'$'}a) right: field${index + 1} @include(if: ${'$'}b) }",
                                                variableField = field,
                                            )
                                        },
                                ) { _, _, _ -> 1 }
                            field to
                                if (index == depth) {
                                    resolver
                                } else {
                                    resolver.withVariablesProvider(setOf("a", "b")) { mapOf("a" to true, "b" to true) }
                                }
                        }
                    },
                    fieldCheckers = { schema ->
                        val checked = schema.loweredSchema.requireObjectField("Query", "field$depth")
                        val pair =
                            ResolverFragmentTemplates(
                                schema.fragmentFrom("fragment CheckerInput on Query { audit }").materializeSelections,
                                materializeSelectionForestOf(),
                            )
                        mapOf(
                            checked to
                                FieldCheckerResolver.of(
                                    checked,
                                    schema.loweredSchema.requireQueryTypeDef(),
                                    mapOf("input" to pair),
                                ) { _, _, _ -> CheckerResult.Success },
                        )
                    },
                )
            val closed = close(fixture.assumptions, fixture, "field0")
            val checkerInput =
                closed.queryRooted.constructionDemand.values
                    .merge(fixture.schema.requireQueryTypeDef())
                    .byKey()
                    .values
                    .single { it.key.field.name == "audit" }
            val nodes = checkerInput.inclusionCondition.nodeCount()
            assertTrue(
                nodes <= 8 * (depth + 1),
                "Field-checker input accumulated $nodes condition nodes at depth $depth",
            )
        }
    }

    @Test
    fun `fixed registry keeps query scaled guarded checker demand linear`() {
        val fixture =
            TestWorld.fromSDL(
                schemaSDL = "type Query { requested(id: Int!): Int shared: Int audit: Int }",
                selectiveResolvers = true,
                fieldResolvers = { schema ->
                    val requested = schema.loweredSchema.requireObjectField("Query", "requested")
                    val empty = schema.loweredSchema.emptyFragmentOf("Query")
                    mapOf(
                        requested to
                            fieldResolverOf(
                                objectFragment = empty,
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment Input on Query { left: shared @include(if: ${'$'}left) right: shared @include(if: ${'$'}right) }",
                                        variableField = requested,
                                    ),
                            ) { _, _, _ -> 1 }
                                .withVariablesProvider(setOf("left", "right")) {
                                    mapOf("left" to true, "right" to true)
                                },
                        schema.loweredSchema.requireObjectField("Query", "shared") to
                            fieldResolverOf(empty) { _, _, _ -> 1 },
                        schema.loweredSchema.requireObjectField("Query", "audit") to
                            fieldResolverOf(empty) { _, _, _ -> 1 },
                    )
                },
                fieldCheckers = { schema ->
                    val shared = schema.loweredSchema.requireObjectField("Query", "shared")
                    val pair =
                        ResolverFragmentTemplates(
                            schema.loweredSchema.emptyFragmentOf("Query").materializeSelections,
                            schema.fragmentFrom("fragment CheckerInput on Query { audit }").materializeSelections,
                        )
                    mapOf(
                        shared to
                            FieldCheckerResolver.of(
                                shared,
                                schema.loweredSchema.requireQueryTypeDef(),
                                mapOf("input" to pair),
                            ) { _, _, _ -> CheckerResult.Success },
                    )
                },
            )
        val world = fixture.assumptions
        val queryType = world.schema.requireQueryTypeDef()

        for (width in listOf(4, 8, 16, 32)) {
            val definitions = (0 until width).joinToString(", ") { "${'$'}include$it: Boolean!" }
            val fields =
                (0 until width).joinToString("\n") { index ->
                    "requested$index: requested(id: $index) @include(if: ${'$'}include$index)"
                }
            val operation =
                fixture.schemas.operationSelectionsFrom(
                    "query($definitions) { $fields }",
                    variables = (0 until width).associate { "include$it" to true },
                )
            assertTrue(
                operation.all { it.inclusionCondition === InclusionCondition.Always },
                "External operation guards must be grounded before Resolution",
            )

            val closed = close(world, operation)
            val audit =
                closed.queryRooted.constructionDemand.values
                    .merge(queryType)
                    .byKey()
                    .values
                    .single { it.key.field.name == "audit" }
            val nodes = audit.inclusionCondition.nodeCount()
            assertTrue(
                nodes <= 8 * width,
                "A fixed guarded resolver and checker closure must add constant demand per requested output",
            )
        }
    }

    @Test
    fun `wide client request activates compact guarded demand without recursion`() {
        val fixture =
            TestWorld.fromSDL(
                schemaSDL = "type Query { requested(id: Int!): Int shared: Int }",
                fieldResolvers = { schema ->
                    val requested = schema.loweredSchema.requireObjectField("Query", "requested")
                    val shared = schema.loweredSchema.requireObjectField("Query", "shared")
                    val empty = schema.loweredSchema.emptyFragmentOf("Query")
                    mapOf(
                        requested to
                            fieldResolverOf(
                                objectFragment = empty,
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment Input on Query { a: shared @include(if: ${'$'}enabled) b: shared @skip(if: ${'$'}disabled) }",
                                        variableField = requested,
                                    ),
                            ) { _, _, _ -> 1 }
                                .withVariablesProvider(setOf("enabled", "disabled")) {
                                    mapOf("enabled" to true, "disabled" to false)
                                },
                        shared to fieldResolverOf(empty) { _, _, _ -> 1 },
                    )
                },
            )
        val width = 1_800
        val fields = (0 until width).joinToString(" ") { index -> "r$index: requested(id: $index)" }
        val selections = fixture.schemas.fragmentFrom("fragment Input on Query { $fields }").subselections

        val result = SharedOperationContext.create(fixture.assumptions).resolveWithTestDispatcher(selections)

        val requested = result.keys.filter { it.field.name == "requested" }
        assertEquals(width, requested.size)
        requested.forEach { key -> assertEquals(1, result.getCell(key).value.get()) }
    }

    @Test
    fun `type checker input demand keeps guarded alternatives compact`() {
        for (depth in listOf(3, 6, 9, 12)) {
            val fixture =
                TestWorld.fromSDL(
                    schemaSDL = "type Query { seed: Int } type Box { audit: Int }",
                    typeCheckers = { schema ->
                        val type = schema.loweredSchema.requireObjectField("Box", "audit").containingDef
                        val pair =
                            ResolverFragmentTemplates(
                                schema.fragmentFrom("fragment CheckerInput on Box { audit }").materializeSelections,
                                materializeSelectionForestOf(),
                            )
                        mapOf(
                            type to
                                TypeCheckerResolver.of(
                                    type,
                                    schema.loweredSchema.requireQueryTypeDef(),
                                    mapOf("input" to pair),
                                ) { _, _ -> CheckerResult.Success },
                        )
                    },
                )
            val world = fixture.assumptions
            val queryType = world.schema.requireQueryTypeDef()
            val boxType = world.schema.requireObjectField("Box", "audit").containingDef
            val objectResult = ObjectEngineResult.of(boxType, mutable = true)
            val owner = ResolverOccurrenceId.at(objectResult, emptyList())
            val variableTarget = world.schema.requireObjectField("Query", "seed")
            var condition: InclusionCondition = InclusionCondition.Always
            repeat(depth) { index ->
                val left = Arguments.Variable.of(variableTarget, "left$index").instantiate(owner)
                val right = Arguments.Variable.of(variableTarget, "right$index").instantiate(owner)
                condition =
                    condition.and(
                        InclusionCondition.anyOf(
                            listOf(
                                InclusionCondition.requires(mapOf(left to true)),
                                InclusionCondition.requires(mapOf(right to true)),
                            ),
                        ),
                    )
            }
            val queryResult = ObjectEngineResult.of(queryType, mutable = true)
            val closed =
                world.schema.objectOf("Box").closeOrchestrationConstructionDemand(
                    world = world,
                    objectOccurrence = OEROccurrence(objectResult, emptyList(), objectResult),
                    queryOccurrence = OEROccurrence(queryResult, emptyList(), queryResult),
                    initialDemand =
                        OrchestrationConstructionDemand(
                            objectRooted =
                                Demand(
                                    checked = viaduct.engine.runtime2.model.selectionForestOf(),
                                    unchecked = viaduct.engine.runtime2.model.selectionForestOf(),
                                    typeCheckDemanded = true,
                                    typeCheckCondition = condition,
                                ),
                            queryRooted = Demand.EMPTY,
                        ),
                )
            val checkerInput =
                closed.objectRooted.constructionDemand.unchecked
                    .byKey()
                    .values
                    .single { it.key.field.name == "audit" }
            val nodes = checkerInput.inclusionCondition.nodeCount()
            assertTrue(
                nodes <= 8 * depth,
                "Type-checker input accumulated $nodes condition nodes at depth $depth",
            )
        }
    }

    @Test
    fun `closure work stays linear across a resolver chain`() {
        for (depth in listOf(16, 32, 64, 128)) {
            val fields = (0..depth).joinToString("\n") { index ->
                val inputs = if (index == depth) "" else "field${index + 1}"
                "field$index: Int @resolver(of: \"$inputs\", result: 1)"
            }
            val fixture = TestWorld.fromDSL("extend type Query { $fields }")
            val original = fixture.assumptions
            var membershipChecks = 0
            val registry =
                object : ResolverRegistry by original.resolverRegistry {
                    override fun contains(field: ViaductSchema.ObjectField): Boolean {
                        membershipChecks++
                        return field in original.resolverRegistry
                    }
                }
            val world = Assumptions.of(original.schema, registry, original.selectiveResolvers)
            membershipChecks = 0
            val closed = close(world, fixture, "field0")
            assertEquals(depth + 1, closed.objectRooted.constructionDemand.values.size)
            assertTrue(
                membershipChecks <= 16 * (depth + 1),
                "Closure performed $membershipChecks membership checks for ${depth + 1} keys",
            )
        }
    }

    @Test
    @Disabled("Exact occurrence-owned arguments still permit exponential symbolic-key multiplicity")
    fun `equal valued diamond arguments do not multiply symbolic keys`() {
        for (depth in listOf(4, 8, 12)) {
            val fields = (0..depth).joinToString("\n") { index ->
                val inputs = ((index + 1)..minOf(index + 2, depth)).joinToString(" ") { "field$it(x: ${'$'}x)" }
                "field$index(x: Int!): Int @resolver(of: \"$inputs\", result: 1)"
            }
            val fixture = TestWorld.fromDSL("extend type Query { $fields }")
            val closed = close(fixture.assumptions, fixture, "field0(x: 1)")
            val keys = closed.objectRooted.constructionDemand.values.size
            assertTrue(
                keys <= 2 * (depth + 1),
                "Closure created $keys exact symbolic keys for ${depth + 1} coordinates",
            )
        }
    }

    @Test
    fun `hashing a linear symbolic key DAG does not revisit its shared predecessor`() {
        for (depth in listOf(4, 8, 12, 16)) {
            val fields = (0..depth).joinToString(" ") { "field$it(x: Int!, y: Int!): Int" }
            val schema = TestWorld.fromSDL("type Query { $fields }").schema
            val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
            val baseField = schema.requireObjectField("Query", "field0")
            var leafHashes = 0
            val countedField =
                object : ViaductSchema.ObjectField by baseField {
                    override fun hashCode(): Int {
                        leafHashes++
                        return baseField.hashCode()
                    }
                }
            var key: ObjectEngineResult.ObjectKey = ObjectEngineResult.GroundKey.of(countedField, mapOf("x" to 1, "y" to 1))
            for (index in 1..depth) {
                val previousField = schema.requireObjectField("Query", "field${index - 1}")
                val variable =
                    Arguments.Variable
                        .of(previousField, "x")
                        .instantiate(ResolverOccurrenceId.at(root, listOf(key)))
                key =
                    ObjectEngineResult.ObjectKey.of(
                        schema.requireObjectField("Query", "field$index"),
                        mapOf("x" to variable, "y" to variable),
                    )
            }
            leafHashes = 0
            key.hashCode()
            assertTrue(
                leafHashes <= depth,
                "One final-key hash revisited the shared base field $leafHashes times at depth $depth",
            )
        }
    }

    @Test
    @Disabled("Cycle detection still runs a fresh reachability search for every dependency edge")
    fun `cycle checking stays linear across an acyclic chain`() {
        for (depth in listOf(16, 32, 64, 128)) {
            val fields = (0..depth).joinToString(" ") { "field$it: Int" }
            val schema = TestWorld.fromSDL("type Query { $fields }").schema
            val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), mutable = true)
            var fieldHashes = 0
            val keys =
                (0..depth).map { index ->
                    val field = schema.requireObjectField("Query", "field$index")
                    val countedField =
                        object : ViaductSchema.ObjectField by field {
                            override fun hashCode(): Int {
                                fieldHashes++
                                return field.hashCode()
                            }
                        }
                    ObjectEngineResult.GroundKey.of(countedField, emptyMap())
                }
            val cells = keys.map { root.reserveCell(it) }
            val checker = CycleCheckState.create()
            keys.forEachIndexed { index, key ->
                checker.registerWriter(cells[index].valueCycleSlot, root.fieldResolverCycleTask(listOf(key)))
            }
            fieldHashes = 0
            for (index in depth - 1 downTo 0) {
                checker.cycleCheck(
                    root.fieldResolverCycleTask(listOf(keys[index])),
                    cells[index + 1].valueCycleSlot,
                )
            }
            assertTrue(
                fieldHashes <= 16 * depth,
                "Cycle checking hashed path fields $fieldHashes times for $depth acyclic edges",
            )
        }
    }

    private fun close(
        world: Assumptions,
        fixture: TestWorld,
        fields: String,
    ): ClosedConstructionDemandContext =
        close(
            world,
            fixture.schemas.fragmentFrom("fragment F on Query { $fields }").subselections,
        )

    private fun close(
        world: Assumptions,
        selections: SelectionForest,
    ): ClosedConstructionDemandContext {
        val type = world.schema.requireQueryTypeDef()
        val objectResult = ObjectEngineResult.of(type, mutable = true)
        val queryResult = ObjectEngineResult.of(type, mutable = true)
        return world.resolverRegistry.createRootQueryInput().closeOrchestrationConstructionDemand(
            world = world,
            objectOccurrence = OEROccurrence(objectResult, emptyList(), objectResult),
            queryOccurrence = OEROccurrence(queryResult, emptyList(), queryResult),
            initialDemand =
                OrchestrationConstructionDemand.checkedObject(
                    selections,
                ),
        )
    }
}
