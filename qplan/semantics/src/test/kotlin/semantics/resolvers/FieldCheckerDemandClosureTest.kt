package semantics.resolvers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import model.Arguments
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.ResolverOccurrenceId
import model.Selection
import model.SelectionForest
import model.VariableBinding
import model.fragmentFrom
import model.lowering.ViaductAndGJSchema
import model.materializeSelectionForestOf
import model.merge
import model.objectOf
import model.registry.FieldCheckerResolver
import model.registry.ResolverFragmentTemplates
import model.requireObjectField
import model.requireQueryTypeDef
import model.selectionForestOf
import model.testing.TestWorld
import semantics.shared.Demand
import semantics.shared.OEROccurrence
import semantics.shared.OrchestrationConstructionDemand
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/** Closure witnesses only: no checker executors, result slots, or Query producers run here. */
class FieldCheckerDemandClosureTest {
    @Test
    fun `raw checker input activates a value resolver whose own input is checked`() {
        val closed = close(chainWorld(), checked = "a")
        assertDemand(closed, checked = setOf("a", "c"), unchecked = setOf("b", "audit"))
    }

    @Test
    fun `source supplied raw input does not expand its standard resolver or checker`() {
        val world = chainWorld()
        val closed = close(
            world,
            checked = "a",
            source = world.schema.objectOf("Box") {
                "a" setTo 1
                "b" setTo 2
            }
        )
        assertDemand(closed, checked = setOf("a"), unchecked = setOf("b"))
    }

    @Test
    fun `overlapping named inputs and later checked demand coalesce by grounded key`() {
        val closed = close(chainWorld(), checked = "a late")
        assertDemand(
            closed,
            checked = setOf("a", "late", "middle", "b", "c"),
            unchecked = setOf("b", "audit", "forbidden"),
        )
    }

    @Test
    fun `checker-free registry adds only ordinary resolver demand`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query { boxes: [Box] @resolver(result: []) }
            type Box {
              a: Int @resolver(of: "b", result: 1)
              b: Int @resolver(of: "c", result: 1)
              c: Int
            }
            """.trimIndent(),
        )

        assertDemand(
            close(world, checked = "a", source = world.schema.objectOf("Box")),
            checked = setOf("a", "b", "c"),
            unchecked = emptySet(),
        )
    }

    @Test
    fun `raw and checked child selections remain separate through overlap`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query { boxes: [Box] @resolver(result: []) }
            type Box { a: Int, nested: Nested }
            type Nested {
              ordinary: Int
              raw: Int @resolver(of: "dependency", result: 1)
              dependency: Int
              forbidden: Int
            }
            """.trimIndent(),
            fieldCheckers = { schema ->
                mapOf(
                    checker(schema, "Box", "a", "nested { raw }"),
                    checker(schema, "Nested", "raw", "forbidden"),
                )
            },
        )

        val closed = close(world, checked = "a nested { ordinary }")
        val nestedKey = key(world, "Box", "nested")
        val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), emptyMap())
        val target = ObjectEngineResult.of(nestedKey.field.type.baseTypeDef as ViaductSchema.Object, emptyMap())
        val child = world.schema.objectOf("Nested").closeConstructionDemand(
            SharedOperationContext.create(world.assumptions),
            OEROccurrence(root, listOf(nestedKey), target),
            initialDemand = Demand(
                checked = closed.checked[nestedKey].subselections,
                unchecked = closed.unchecked[nestedKey].subselections,
                typeCheckDemanded = true,
            ),
        )
        assertDemand(child, checked = setOf("ordinary", "dependency"), unchecked = setOf("raw"))
    }

    @Test
    fun `raw argument demand binds once per exact root list position and grounded key`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query { boxes: [Box] @resolver(result: []) }
            type Box {
              a: Int
              late: Int @resolver(of: "middle", result: 1)
              middle: Int @resolver(of: "b(seed: 7)", result: 1)
              b(seed: Int!): Int @resolver(of: "c(value: ${'$'}seed)", result: 1)
              c(value: Int!): Int @resolver(result: 1)
            }
            """.trimIndent(),
            fieldCheckers = { schema -> mapOf(checker(schema, "Box", "a", "first: b(seed: 7) second: b(seed: 8)")) },
        )

        val operation = SharedOperationContext.create(world.assumptions)
        repeat(2) {
            val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), emptyMap())
            repeat(2) { index ->
                val path = listOf(key(world, "Query", "boxes"), ListEngineResult.Index.of(index))
                val target = ObjectEngineResult.of(world.schema.requireObjectField("Box", "a").containingDef, emptyMap())
                val closed = world.schema.objectOf("Box").closeConstructionDemand(
                    operation,
                    OEROccurrence(root, path, target),
                    Demand.checked(selections(world, "Box", "a late")),
                )
                val b7 = key(world, "Box", "b", mapOf("seed" to 7))
                val b8 = key(world, "Box", "b", mapOf("seed" to 8))
                val c7 = key(world, "Box", "c", mapOf("value" to 7))
                val c8 = key(world, "Box", "c", mapOf("value" to 8))
                assertEquals(
                    setOf(key(world, "Box", "a"), key(world, "Box", "late"), key(world, "Box", "middle"), b7, b8, c7, c8),
                    closed.values.merge(closed.checked.type).groundKeys(),
                )
                assertEquals(closed.values.merge(closed.checked.type).groundKeys() - b8, closed.checked.groundKeys())
                assertEquals(setOf(b7, b8), closed.unchecked.groundKeys())
                for ((key, value) in listOf(b7 to 7, b8 to 8)) {
                    val variable = Arguments.Variable.of(key.field, "seed")
                        .instantiate(ResolverOccurrenceId.at(root, path + key))
                    assertEquals(VariableBinding.of(value), operation.variableBindings.getBinding(requireNotNull(variable.instanceId)))
                }
            }
        }
    }

    @Test
    fun `checker Query inputs join the associated Query closure as raw demand`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              boxes: [Box] @resolver(result: [])
              viewer: Int @resolver(of: "dependency", result: 1)
              dependency: Int @resolver(result: 1)
              forbidden: Int @resolver(result: 1)
            }
            type Box { a: Int }
            """.trimIndent(),
            fieldCheckers = { schema ->
                val a = schema.loweredSchema.requireObjectField("Box", "a")
                mapOf(
                    a to FieldCheckerResolver.of(
                        a,
                        schema.loweredSchema.requireQueryTypeDef(),
                        fragmentTemplates = mapOf(
                            "empty" to
                                ResolverFragmentTemplates(
                                    objectFragmentTemplate = materializeSelectionForestOf(),
                                    queryFragmentTemplate = materializeSelectionForestOf(),
                                ),
                            "viewer" to
                                ResolverFragmentTemplates(
                                    objectFragmentTemplate = materializeSelectionForestOf(),
                                    queryFragmentTemplate =
                                        schema
                                            .fragmentFrom("fragment F on Query { viewer }")
                                            .materializeSelections,
                                ),
                            "emptyQuery" to
                                ResolverFragmentTemplates(
                                    objectFragmentTemplate = materializeSelectionForestOf(),
                                    queryFragmentTemplate = materializeSelectionForestOf(),
                                ),
                        ),
                    ) { _, _, _ -> error("Closure must not execute checkers") },
                    checker(schema, "Query", "viewer", "forbidden"),
                )
            },
        )

        val checker = requireNotNull(world.resolverRegistry.fieldChecker(world.schema.requireObjectField("Box", "a")))
        assertEquals(setOf("empty", "viewer", "emptyQuery"), checker.fragmentTemplates.keys)
        val operationRoot = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), emptyMap())
        val queryRoot = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), emptyMap())
        val objectTarget = ObjectEngineResult.of(world.schema.requireObjectField("Box", "a").containingDef, emptyMap())
        val closed =
            world.schema.objectOf("Box") { "a" setTo 1 }.closeOrchestrationConstructionDemand(
                operation = SharedOperationContext.create(world.assumptions),
                objectOccurrence =
                    OEROccurrence(
                        operationRoot,
                        listOf(key(world, "Query", "boxes"), ListEngineResult.Index.of(0)),
                        objectTarget,
                    ),
                queryOccurrence = OEROccurrence(queryRoot, emptyList(), queryRoot),
                initialDemand =
                    OrchestrationConstructionDemand(
                        objectRooted = Demand.checked(selections(world, "Box", "a")),
                        queryRooted = Demand.EMPTY,
                    ),
            )
        assertDemand(closed.objectRooted, checked = setOf("a"), unchecked = emptySet())
        assertDemand(
            closed.queryRooted,
            checked = setOf("dependency"),
            unchecked = setOf("viewer"),
        )
        assertTrue(objectTarget.keys.isEmpty(), "Closure must not allocate result cells")
        assertTrue(operationRoot.keys.isEmpty(), "Closure must not allocate result cells")
        assertTrue(queryRoot.keys.isEmpty(), "Closure must not allocate result cells")
    }

    @Test
    fun `checker value cycle reaches a demand fixed point even when the schema has parents`() {
        val world = parentWorld(aInput = "b", bInput = "a")
        assertDemand(close(world, checked = "a"), checked = setOf("a"), unchecked = setOf("b"))
    }

    @Test
    fun `raw parent reads do not request checks but raw active resolver parent inputs do`() {
        val world = parentWorld(aInput = "children { parent { rawAncestor } derived }")
        assertDemand(
            close(world, checked = "a"),
            checked = setOf("a", "checkedAncestor"),
            unchecked = setOf("children", "rawAncestor", "audit"),
        )
    }

    @Test
    fun `checked parent reads remain checked across nested lists and two parent edges`() {
        val world = parentWorld(aInput = "children { grandchildren { derived } }")
        assertDemand(
            close(world, checked = "a"),
            checked = setOf("a", "checkedAncestor"),
            unchecked = setOf("children", "audit", "checkedAncestor"),
        )
    }

    @Test
    fun `descendant checker inputs lift raw ancestor demand before passive descent`() {
        val world = parentWorld(aInput = "audit")
        assertDemand(
            close(world, checked = "children { protected }"),
            checked = setOf("children"),
            unchecked = setOf("rawAncestor", "forbidden"),
        )
    }

    @Test
    fun `checked work below a raw path is rediscovered without checking traversal fields`() {
        val world = parentWorld(aInput = "children { grandchildren { localDerived } }")
        val closed = close(world, checked = "a")
        assertDemand(closed, checked = setOf("a"), unchecked = setOf("children"))
        val children = key(world, "Box", "children")
        val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), emptyMap())
        val child = ObjectEngineResult.of(world.schema.requireObjectField("Child", "parent").containingDef, emptyMap())
        val childClosed = world.schema.objectOf("Child").closeConstructionDemand(
            SharedOperationContext.create(world.assumptions),
            OEROccurrence(root, listOf(children, ListEngineResult.Index.of(0), ListEngineResult.Index.of(0)), child),
            initialDemand = Demand.unchecked(closed.unchecked[children].subselections),
        )
        assertDemand(
            childClosed,
            checked = setOf("checkedLocal"),
            unchecked = setOf("grandchildren", "checkedLocal", "localAudit"),
        )
    }

    @Test
    fun `error arguments do not expand checker required selections`() {
        val world = chainWorld()
        val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), emptyMap())
        val field = world.schema.requireObjectField("Box", "a")
        val target = ObjectEngineResult.of(field.containingDef, emptyMap())
        val errored = ObjectEngineResult.GroundKey.of(field, Arguments.Error)
        val closed = world.schema.objectOf("Box").closeConstructionDemand(
            SharedOperationContext.create(world.assumptions),
            OEROccurrence(root, emptyList(), target),
            initialDemand = Demand.checked(
                selectionForestOf(Selection.of(errored, setOf(field.containingDef), selectionForestOf())),
            ),
        )
        assertEquals(setOf(errored), closed.values.merge(closed.checked.type).groundKeys())
        assertEquals(setOf(errored), closed.checked.groundKeys())
        assertTrue(closed.unchecked.isEmpty())
    }

    @Test
    fun `cyclic parent analysis is independent of which input is visited first`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query { boxes: [Box] @resolver(result: []) }
            type Box {
              a: Int
              children: [Child]
              rawA: Int
              rawB: Int
              checkedB: Int
              audit: Int
            }
            type Child {
              parent: Box @parent
              a: Int
              b: Int @resolver(of: "a parent { checkedB }", result: 1)
            }
            """.trimIndent(),
            fieldCheckers = { schema ->
                mapOf(
                    checker(schema, "Child", "a", "b parent { rawA }"),
                    checker(schema, "Child", "b", "parent { rawB }"),
                    checker(schema, "Box", "checkedB", "audit"),
                )
            },
        )

        for (fields in listOf("a b", "b a")) {
            assertDemand(
                close(world, checked = "children { $fields }"),
                checked = setOf("children", "checkedB"),
                unchecked = setOf("rawA", "rawB", "audit"),
            )
        }
    }

    private fun chainWorld(): TestWorld =
        TestWorld.fromDSL(
            """
        extend type Query { boxes: [Box] @resolver(result: []) }
        type Box {
          a: Int
          b: Int @resolver(of: "c", result: 1)
          c: Int
          audit: Int
          forbidden: Int
          late: Int @resolver(of: "middle", result: 1)
          middle: Int @resolver(of: "b", result: 1)
        }
            """.trimIndent(),
            fieldCheckers = { schema ->
                mapOf(
                    checker(schema, "Box", "a", "b", "b"),
                    checker(schema, "Box", "b", "forbidden"),
                    checker(schema, "Box", "c", "audit"),
                )
            },
        )

    private fun parentWorld(
        aInput: String,
        bInput: String = "checkedAncestor"
    ): TestWorld =
        TestWorld.fromDSL(
            """
        extend type Query { boxes: [Box] @resolver(result: []) }
        type Box {
          a: Int
          b: Int @resolver(of: "$bInput", result: 1)
          children: [[Child]]
          rawAncestor: Int
          checkedAncestor: Int
          audit: Int
          forbidden: Int
        }
        type Child {
          parent: Box @parent
          protected: Int
          derived: Int @resolver(of: "parent { checkedAncestor }", result: 1)
          grandchildren: [[Grandchild]]
          checkedLocal: Int
          localAudit: Int
        }
        type Grandchild {
          parent: Child @parent
          derived: Int @resolver(of: "parent { parent { checkedAncestor } }", result: 1)
          localDerived: Int @resolver(of: "parent { checkedLocal }", result: 1)
        }
            """.trimIndent(),
            fieldCheckers = { schema ->
                mapOf(
                    checker(schema, "Box", "a", aInput),
                    checker(schema, "Box", "children", "forbidden"),
                    checker(schema, "Box", "rawAncestor", "forbidden"),
                    checker(schema, "Box", "checkedAncestor", "audit"),
                    checker(schema, "Child", "derived", "parent { forbidden }"),
                    checker(schema, "Child", "protected", "parent { rawAncestor }"),
                    checker(schema, "Child", "checkedLocal", "localAudit"),
                )
            },
        )

    private fun checker(
        schema: ViaductAndGJSchema,
        type: String,
        name: String,
        vararg inputs: String,
    ): Pair<ViaductSchema.ObjectField, FieldCheckerResolver> {
        val field = schema.loweredSchema.requireObjectField(type, name)
        return field to FieldCheckerResolver.of(
            field,
            schema.loweredSchema.requireQueryTypeDef(),
            fragmentTemplates =
                inputs.mapIndexed { index, input ->
                    "input$index" to
                        ResolverFragmentTemplates(
                            objectFragmentTemplate =
                                schema
                                    .fragmentFrom("fragment F on $type { $input }")
                                    .materializeSelections,
                            queryFragmentTemplate = materializeSelectionForestOf(),
                        )
                }.toMap(),
        ) { _, _, _ -> error("Closure must not execute checkers") }
    }

    private fun close(
        world: TestWorld,
        checked: String,
        source: EngineObjectData.Sync = world.schema.objectOf("Box") { "a" setTo 1 },
    ): Demand<ObjectSelectionForest> {
        val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), emptyMap())
        val target = ObjectEngineResult.of(world.schema.requireObjectField("Box", "a").containingDef, emptyMap())
        val occurrence = OEROccurrence(root, listOf(key(world, "Query", "boxes"), ListEngineResult.Index.of(0)), target)
        val closed = source.closeConstructionDemand(
            SharedOperationContext.create(world.assumptions),
            occurrence,
            Demand.checked(selections(world, "Box", checked)),
        )
        assertTrue(target.keys.isEmpty(), "Closure must not allocate result cells")
        return closed
    }

    /** Exercises one side of the paired closure while retaining a real associated Query root. */
    private fun EngineObjectData.Sync.closeConstructionDemand(
        operation: SharedOperationContext<*>,
        occurrence: OEROccurrence,
        initialDemand: Demand<SelectionForest>,
    ): Demand<ObjectSelectionForest> {
        val query = operation.world.schema.requireQueryTypeDef()
        val queryRoot = ObjectEngineResult.of(query, emptyMap())
        return closeOrchestrationConstructionDemand(
            operation = operation,
            objectOccurrence = occurrence,
            queryOccurrence = OEROccurrence(queryRoot, emptyList(), queryRoot),
            initialDemand =
                OrchestrationConstructionDemand(
                    objectRooted = initialDemand,
                    queryRooted = Demand.EMPTY,
                ),
        ).objectRooted
    }

    private fun selections(
        world: TestWorld,
        type: String,
        fields: String
    ): SelectionForest = world.schemas.fragmentFrom("fragment F on $type { $fields }").subselections

    private fun key(
        world: TestWorld,
        type: String,
        name: String,
        arguments: Map<String, Any?> = emptyMap(),
    ): ObjectEngineResult.GroundKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField(type, name), arguments)

    private fun assertDemand(
        closed: Demand<ObjectSelectionForest>,
        checked: Set<String>,
        unchecked: Set<String>
    ) {
        assertEquals(checked, closed.checked.groundKeys().map { it.field.name }.toSet(), "checked demand")
        assertEquals(unchecked, closed.unchecked.groundKeys().map { it.field.name }.toSet(), "unchecked demand")
        assertEquals(
            checked + unchecked,
            closed.values.merge(closed.checked.type).groundKeys().map { it.field.name }.toSet(),
            "value demand",
        )
    }
}
