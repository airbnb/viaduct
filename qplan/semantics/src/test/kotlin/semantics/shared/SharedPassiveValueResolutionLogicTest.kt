package semantics.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import model.EngineObjectDataEntry
import model.EngineResult
import model.EngineResultCell
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.SelectionForest
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.fragmentFrom
import model.objectOf
import model.outputType
import model.requireField
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.testing.TestWorld
import model.testing.fieldResolverOf
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

class SharedPassiveValueResolutionLogicTest {
    @Test
    fun `leaves demanded active typename unresolved and retains exact resolver objects`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Profile {
                      raw: String!
                      rendered: String!
                    }

                    type User {
                      name: String!
                      profile: Profile!
                      computed: String!
                    }

                    type Query {
                      user: User!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "user") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> schema.objectOf("User") },
                        schema.requireField("User", "computed") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, _ -> "computed" },
                        schema.requireField("Profile", "rendered") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Profile"),
                            ) { _, _ -> "rendered" },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val userType = schema.requireType("User") as ViaductSchema.Object
        val profileType = schema.requireType("Profile") as ViaductSchema.Object
        val typeNameKey =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("User", "V_A_typename"),
                emptyMap(),
            )
        val computedKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("User", "computed"), emptyMap())
        val profileKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("User", "profile"), emptyMap())
        val rawKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Profile", "raw"), emptyMap())
        val value =
            schema.objectOf("User") {
                "name" setTo "Ada"
                "profile" setTo
                    objectOf("Profile") {
                        "raw" setTo "engineer"
                    }
            }
        val selections =
            world.fragmentFrom(
                """
                fragment ignored on User {
                  __typename
                  name
                  computed
                  profile {
                    raw
                    rendered
                  }
                }
                """.trimIndent(),
            ).subselections

        val resolved =
            runBlocking {
                SharedOperationContext.create(world).let { resolutionOperation -> value.recordPassiveResolution(
                    operation = resolutionOperation,
                    expectedType = world.schema.requireObjectField("Query", "user").outputType,
                    path = emptyList(),
                    constructionDemand = selections,
                ) }
            }

        val result = assertIs<ObjectEngineResult>(resolved.engineResult)
        assertTrue(typeNameKey !in result.keys)
        assertTrue(computedKey !in result.keys)

        val profile = assertIs<ObjectEngineResult>(result.getCell(profileKey).getValue().get())
        assertEquals(userType, result.type)
        assertEquals(profileType, profile.type)
        assertEquals(setOf(rawKey), profile.keys)
        val resolutionsByPath =
            resolved.pendingObjects.associateBy { passiveObjectOccurrence ->
                passiveObjectOccurrence.path
            }
        assertEquals(setOf(emptyList(), listOf(profileKey)), resolutionsByPath.keys)
        assertSame(result, resolutionsByPath.getValue(emptyList()).target)
        assertEquals(4, resolutionsByPath.getValue(emptyList()).selections.size)
    }

    @Test
    fun `non-selective traversal unpacks every provided passive field but only demanded resolver paths`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = false,
                schemaSDL =
                    """
                    type Profile {
                      raw: String!
                      rendered: String!
                    }

                    type User {
                      name: String!
                      profile: Profile!
                      computed: String!
                    }

                    type Query {
                      user: User!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "user") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> schema.objectOf("User") },
                        schema.requireField("User", "computed") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, _ -> "computed" },
                        schema.requireField("Profile", "rendered") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Profile"),
                            ) { _, _ -> "rendered" },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val nameKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("User", "name"), emptyMap())
        val profileKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("User", "profile"), emptyMap())
        val rawKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Profile", "raw"), emptyMap())
        val value =
            schema.objectOf("User") {
                "name" setTo "Ada"
                "profile" setTo
                    objectOf("Profile") {
                        "raw" setTo "engineer"
                    }
            }
        val constructionDemand =
            world.fragmentFrom(
                "fragment ignored on User { computed }",
            ).subselections

        val resolved =
            runBlocking {
                SharedOperationContext.create(world).let { resolutionOperation -> value.recordPassiveResolution(
                    operation = resolutionOperation,
                    expectedType = world.schema.requireObjectField("Query", "user").outputType,
                    path = emptyList(),
                    constructionDemand = constructionDemand,
                ) }
            }

        val result = assertIs<ObjectEngineResult>(resolved.engineResult)
        assertEquals(setOf(nameKey, profileKey), result.keys)
        val profile = assertIs<ObjectEngineResult>(result.getCell(profileKey).getValue().get())
        assertEquals(setOf(rawKey), profile.keys)
        assertEquals(
            setOf(emptyList()),
            resolved.pendingObjects.map { it.path }.toSet(),
        )
    }

    @Test
    fun `selective traversal rejects an output field outside selections`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      selected: String!
                      extra: String!
                    }

                    type Query {
                      user: User!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val value =
            world.schema.objectOf("User") {
                "selected" setTo "kept"
                "extra" setTo "rejected"
            }
        val selections =
            world.fragmentFrom(
                "fragment ignored on User { selected }",
            ).subselections

        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                SharedOperationContext.create(world).let { resolutionOperation -> value.recordPassiveResolution(
                    operation = resolutionOperation,
                    expectedType = world.schema.requireObjectField("Query", "user").outputType,
                    path = emptyList(),
                    constructionDemand = selections,
                ) }
            }
        }
    }

    @Test
    fun `selective output permits fields in invocation demand beyond construction demand`() {
        val world =
            TestWorld
                .fromSDL(
                    """
                    type Item {
                      computed: Int!
                      seed: Int!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                ).assumptions
        val computedKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Item", "computed"),
                emptyMap(),
            )
        val seedKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Item", "seed"),
                emptyMap(),
            )
        val value =
            world.schema.objectOf("Item") {
                "computed" setTo 7
                "seed" setTo 3
            }
        val constructionDemand =
            world.fragmentFrom("fragment ignored on Item { computed }").subselections
        val invocationDemand =
            world.fragmentFrom("fragment ignored on Item { computed seed }").subselections

        val resolved =
            runBlocking {
                SharedOperationContext.create(world).let { resolutionOperation -> value.recordPassiveResolution(
                    operation = resolutionOperation,
                    expectedType = world.schema.requireObjectField("Query", "item").outputType,
                    path = emptyList(),
                    constructionDemand = constructionDemand,
                    invocationDemand = invocationDemand,
                ) }
            }

        val result = assertIs<ObjectEngineResult>(resolved.engineResult)
        assertEquals(setOf(computedKey, seedKey), result.keys)
    }

    @Test
    fun `missing invocation-only fields do not require downstream resolution`() {
        val world =
            TestWorld
                .fromSDL(
                    """
                    type Item {
                      computed: Int!
                      seed: Int!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                ).assumptions
        val value =
            world.schema.objectOf("Item") {
                "computed" setTo 7
            }
        val constructionDemand =
            world.fragmentFrom("fragment ignored on Item { computed }").subselections
        val invocationDemand =
            world.fragmentFrom("fragment ignored on Item { computed seed }").subselections

        val resolved =
            runBlocking {
                SharedOperationContext.create(world).let { resolutionOperation -> value.recordPassiveResolution(
                    operation = resolutionOperation,
                    expectedType = world.schema.requireObjectField("Query", "item").outputType,
                    path = emptyList(),
                    constructionDemand = constructionDemand,
                    invocationDemand = invocationDemand,
                ) }
            }

        assertEquals(emptyList(), resolved.pendingObjects)
    }

    @Test
    fun `non-selective worlds retain output fields outside selections`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      selected: String!
                      extra: String!
                    }

                    type Query {
                      user: User!
                    }
                    """.trimIndent(),
                selectiveResolvers = false,
            )
        val world = testWorld.assumptions
        val selectedKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("User", "selected"), emptyMap())
        val extraKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("User", "extra"), emptyMap())
        val value =
            world.schema.objectOf("User") {
                "selected" setTo "kept"
                "extra" setTo "ignored"
            }
        val selections =
            world.fragmentFrom(
                "fragment ignored on User { selected }",
            ).subselections

        val resolved =
            runBlocking {
                SharedOperationContext.create(world).let { resolutionOperation -> value.recordPassiveResolution(
                    operation = resolutionOperation,
                    expectedType = world.schema.requireObjectField("Query", "user").outputType,
                    path = emptyList(),
                    constructionDemand = selections,
                ) }
            }

        val result = assertIs<ObjectEngineResult>(resolved.engineResult)
        assertEquals(setOf(selectedKey, extraKey), result.keys)
    }

    @Test
    fun `rejects an argument-bearing passive object field`() {
        val world =
            TestWorld
                .fromSDL(
                    """
                    type Item {
                      value(index: Int): String
                    }

                    type Query {
                      item: Item
                    }
                    """.trimIndent(),
                ).assumptions
        val itemType = world.schema.requireType("Item") as ViaductSchema.Object
        val field = world.schema.requireObjectField("Item", "value")
        val value =
            engineObjectDataOf(
                schemaType = itemType,
                fields =
                    listOf(
                        EngineObjectDataEntry.of(
                            selection = field.name,
                            field = field,
                            value = "one",
                        ),
                    ),
            )
        val selections =
            world.fragmentFrom(
                "fragment ignored on Item { value(index: 1) }",
            ).subselections

        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                SharedOperationContext.create(world).let { resolutionOperation -> value.recordPassiveResolution(
                    operation = resolutionOperation,
                    expectedType = world.schema.requireObjectField("Query", "item").outputType,
                    path = emptyList(),
                    constructionDemand = selections,
                ) }
            }
        }
    }

    @Test
    fun `list traversal records every pending descendant without rebuilding paths`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Nested {
                      rendered: Int!
                    }

                    type Item {
                      nested: Nested!
                      computed: Int!
                    }

                    type Query {
                      items: [Item!]!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val emptyQuery = schema.emptyFragmentOf("Query")
                    val emptyItem = schema.emptyFragmentOf("Item")
                    val emptyNested = schema.emptyFragmentOf("Nested")
                    mapOf(
                        schema.requireField("Query", "items") to
                            fieldResolverOf(emptyQuery) { _, _ ->
                                error("Not invoked")
                            },
                        schema.requireField("Item", "computed") to
                            fieldResolverOf(emptyItem) { _, _ ->
                                error("Not invoked")
                            },
                        schema.requireField("Nested", "rendered") to
                            fieldResolverOf(emptyNested) { _, _ ->
                                error("Not invoked")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val itemsField = schema.requireObjectField("Query", "items")
        val output =
            listOf(
                        schema.objectOf("Item") {
                            "nested" setTo schema.objectOf("Nested")
                        },
                        schema.objectOf("Item") {
                            "nested" setTo schema.objectOf("Nested")
                        },
                    )
        val selections =
            world.fragmentFrom(
                """
                fragment ignored on Item {
                  computed
                  nested {
                    rendered
                  }
                }
                """.trimIndent(),
            ).subselections
        val itemsKey = ObjectEngineResult.GroundKey.of(itemsField, emptyMap())
        val nestedKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Item", "nested"), emptyMap())
        val computedKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Item", "computed"), emptyMap())
        val renderedKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Nested", "rendered"), emptyMap())
        val rootPath = listOf<PathComponent>(itemsKey)
        val expectedRootPaths =
            setOf(
                rootPath + ListEngineResult.Index.of(0),
                rootPath + ListEngineResult.Index.of(1),
            )
        val passiveValuesResult =
            runBlocking {
                SharedOperationContext.create(world).let { resolutionOperation -> output.recordPassiveResolution(
                    operation = resolutionOperation,
                    expectedType = itemsField.outputType,
                    path = rootPath,
                    constructionDemand = selections,
                ) }
            }
        val resolutionsByPath = passiveValuesResult.pendingObjects.associateBy { it.path }
        val expectedPaths = expectedRootPaths + expectedRootPaths.map { it + nestedKey }
        assertEquals(expectedPaths, resolutionsByPath.keys)
        passiveValuesResult.pendingObjects.forEach { occurrence ->
            val key = if (occurrence.target.type.name == "Item") computedKey else renderedKey
            occurrence.target.setCellValue(key, 1)
        }
        val replayed = passiveValuesResult.engineResult

        val result = assertIs<ListEngineResult>(replayed)
        result.forEachIndexed { index, cell ->
            val item = assertIs<ObjectEngineResult>(cell.getValue().get())
            val itemPath = rootPath + ListEngineResult.Index.of(index)
            assertSame(item, resolutionsByPath.getValue(itemPath).target)
            assertEquals(1, item.getCell(computedKey).getValue().get())

            val nested = assertIs<ObjectEngineResult>(item.getCell(nestedKey).getValue().get())
            assertEquals(1, nested.getCell(renderedKey).getValue().get())
        }
    }
}

private class RecordedObject(
    val path: List<PathComponent>,
    val target: ObjectEngineResult,
    val selections: SelectionForest,
)

private class RecordedPassiveResolution(val engineResult: EngineResult?, val pendingObjects: List<RecordedObject>)

/** Tests passive resolution independently of any executor: record each object's unfilled local demand. */
private fun ResolverOutputData?.recordPassiveResolution(
    operation: SharedOperationContext<*>,
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    path: List<PathComponent>,
    constructionDemand: SelectionForest,
    invocationDemand: SelectionForest = constructionDemand,
): RecordedPassiveResolution {
    val pending = mutableListOf<RecordedObject>()
    val taskOperation = SharedOperationContext.create(
        world = operation.world,
        variableBindings = operation.variableBindings,
        resolverObserver = operation.resolverObserver,
        dispatcher = object : SharedTaskDispatcher<SharedOrchestrationTask<*>, SharedFieldPublicationOccurrence<*, *>> {
            override fun dispatchOrchestrator(task: SharedOrchestrationTask<*>) {
                val objectOER = task.objectOER
                if (objectOER.closedDemand.groundKeys().any { it !in objectOER.occurrence.target.keys }) {
                    pending += RecordedObject(objectOER.occurrence.path, objectOER.occurrence.target, objectOER.closedDemand)
                }
            }

            override fun dispatchFieldResolver(publication: SharedFieldPublicationOccurrence<*, *>) =
                error("Executable references are covered by the resolver contracts")
        },
    )
    val resolution = object : SharedPassiveValueResolutionLogic<
        SharedOrchestrationTask<*>,
        SharedOperationContext<SharedTaskDispatcher<SharedOrchestrationTask<*>, *>>,
    >(taskOperation) {
        override fun collect(selections: SelectionForest, type: ViaductSchema.Object): ObjectSelectionForest =
            selections.applicableGroundSelections(operation, type)

        override fun createOrchestrationTask(
            occurrence: OEROccurrence,
            source: EngineObjectData.Sync,
            constructionDemand: SelectionForest,
        ): SharedOrchestrationTask<*> = object : SharedOrchestrationTask<SharedOperationContext<*>> {
            override val operation = taskOperation
            override val objectOER =
                SharedOERContext(
                    occurrence,
                    source,
                    collect(constructionDemand, occurrence.target.type),
                )
            override val queryOER =
                SharedOERContext.undemandedQuery(operation.world.schema.requireQueryTypeDef())
        }

        override fun resolveListReference(
            reference: RootFieldReferenceData,
            cell: EngineResultCell,
            path: List<PathComponent>,
            expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
            selection: ObjectSelection,
            invocationDemand: SelectionForest,
            parent: OEROccurrence,
        ) = error("Executable references are covered by the resolver contracts")
    }
    val root = ObjectEngineResult.of(operation.world.schema.requireQueryTypeDef(), mutable = true)
    val result = resolution.resolvePassiveValues(
        this, root, expectedType, path, constructionDemand, invocationDemand,
        OEROccurrence(root, emptyList(), root),
    )
    return RecordedPassiveResolution(result, pending)
}
