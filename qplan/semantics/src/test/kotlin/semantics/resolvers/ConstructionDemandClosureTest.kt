package semantics.resolvers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import model.Arguments
import model.EngineObjectDataEntry
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.ResolverOccurrenceId
import model.Selection
import model.SelectionForest
import model.VariableBinding
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.fragmentFrom
import model.merge
import model.objectOf
import model.requireObjectField
import model.requireQueryTypeDef
import model.schemaType
import model.selectionForestOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.resolvers.resolver01.SiblingDependencyLogic
import semantics.shared.Demand
import semantics.shared.OEROccurrence
import semantics.shared.OrchestrationConstructionDemand
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

class ConstructionDemandClosureTest {
    @Test
    fun `empty object closure produces no Query rooted demand`() {
        val fixture = queryClosureFixture()
        val schema = fixture.assumptions.schema
        val closed =
            fixture.closeOrchestrationDemand(
                OrchestrationConstructionDemand.checkedObject(
                    schema.fragmentFrom("fragment F on Query { leaf }").subselections,
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
        val schema = fixture.assumptions.schema
        val closed =
            fixture.closeOrchestrationDemand(
                OrchestrationConstructionDemand.checkedObject(
                    schema.fragmentFrom("fragment F on Query { entry }").subselections,
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
        val schema = fixture.assumptions.schema
        val closed =
            fixture.closeOrchestrationDemand(
                OrchestrationConstructionDemand.checkedObject(
                    schema.fragmentFrom("fragment F on Query { entry }").subselections,
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
        val schema = fixture.assumptions.schema
        val ownerSelections =
            schema.fragmentFrom("fragment F on Query { checkedOwner }").subselections
        val rawOwnerSelections =
            schema.fragmentFrom("fragment F on Query { rawOwner }").subselections
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
        val schema = fixture.assumptions.schema
        val childResult =
            schema.fragmentFrom("fragment F on Root { child { result } }").subselections
        val closed =
            fixture.closeOrchestrationDemand(
                OrchestrationConstructionDemand.checkedObject(childResult),
                objectTypeName = "Root",
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

    @Test
    fun `query rooted parent lifting remains below the Query root`() {
        val fixture = parentClosureFixture()
        val schema = fixture.assumptions.schema
        val childResult =
            schema.fragmentFrom("fragment F on Query { root { child { result } } }").subselections
        val closed =
            fixture.closeOrchestrationDemand(
                OrchestrationConstructionDemand(
                    objectRooted = Demand.EMPTY,
                    queryRooted = Demand.unchecked(childResult),
                ),
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

    @Test
    fun `closure and order keep accumulators local to each invocation`() {
        val world = fixture().assumptions
        val schema = world.schema
        val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())
        val occurrence = OEROccurrence(root, emptyList(), root)
        val operation = SharedOperationContext.create(world)
        val source = schema.objectOf("Query")
        val ordering = SiblingDependencyLogic(operation, occurrence)
        val a = key(world, "Query", "a")
        val b = key(world, "Query", "b")
        val leaf = key(world, "Query", "leaf")
        val demand = schema.fragmentFrom("fragment F on Query { a }").subselections

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
        val world = fixture().assumptions
        val schema = world.schema
        val operation = SharedOperationContext.create(world)
        val boxType = schema.requireObjectField("Box", "consumer").containingDef
        val boxes = key(world, "Query", "boxes")
        val consumer = key(world, "Box", "consumer", mapOf("seed" to 7))
        val sibling = key(world, "Box", "sibling", mapOf("value" to 7))
        val demand =
            schema.fragmentFrom("fragment F on Box { consumer(seed: 7) }").subselections

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
        val world = fixture().assumptions
        val schema = world.schema
        val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())
        val source = schema.objectOf("Query") { "a" setTo 99 }
        val operation = SharedOperationContext.create(world)
        val occurrence = OEROccurrence(root, emptyList(), root)
        val demand = schema.fragmentFrom("fragment F on Query { a }").subselections

        assertEquals(
            setOf(key(world, "Query", "a")),
            source.closeObjectDemand(operation, occurrence, demand)
                .groundKeys(),
        )
    }

    @Test
    fun `passive argument-bearing source validation retains error argument precedence`() {
        val world = fixture().assumptions
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
            schema.fragmentFrom("fragment F on Box { consumer(seed: 7) }").subselections
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
        val world = fixture().assumptions
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
        val world = fixture().assumptions
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
            schema.fragmentFrom("fragment F on Box { consumer(seed: 7) }").subselections
        source.closeObjectDemand(operation, occurrence, demand)

        assertFailsWith<IllegalStateException> {
            source.closeObjectDemand(operation, occurrence, demand)
        }
    }

    private fun key(
        world: model.Assumptions,
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
                val empty = schema.emptyFragmentOf("Query")
                val entry = schema.requireObjectField("Query", "entry")
                val queryOnly = schema.requireObjectField("Query", "queryOnly")
                val checkedOwner = schema.requireObjectField("Query", "checkedOwner")
                val rawOwner = schema.requireObjectField("Query", "rawOwner")
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
                    val field = schema.requireObjectField("Query", fieldName)
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
                val emptyQuery = schema.emptyFragmentOf("Query")
                val emptyRoot = schema.emptyFragmentOf("Root")
                val child = schema.requireObjectField("Root", "child")
                val lifted = schema.requireObjectField("Root", "lifted")
                val local = schema.requireObjectField("Root", "local")
                val queryInput = schema.requireObjectField("Query", "queryInput")
                val transitive = schema.requireObjectField("Query", "transitive")
                val result = schema.requireObjectField("Child", "result")
                mapOf(
                    child to
                        fieldResolverOf(emptyRoot) { _, _ -> schema.objectOf("Child") },
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
    ): OrchestrationConstructionDemand<ObjectSelectionForest> {
        val world = assumptions
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

    private fun ObjectSelectionForest.fieldNames(): Set<String> = groundKeys().mapTo(linkedSetOf()) { key -> key.field.name }
}
