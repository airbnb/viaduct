@file:Suppress("ForbiddenImport")

package semantics.resolver26

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.Assumptions
import model.EngineOutputData
import model.EngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.Selection
import model.SelectionForest
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.outputType
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.selectionForestOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.shared.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PassiveValueResolutionLogicTest : Resolver26DispatcherResource {
    @Test
    fun `passive-only object trees freeze synchronously as they are created`() {
        val world =
            TestWorld
                .fromSDL(
                    """
                    type Query {
                      container: Container!
                    }

                    type Container {
                      items: [Item!]!
                      omitted: String
                    }

                    type Item {
                      value: String!
                      omitted: String
                    }
                    """.trimIndent(),
                ).assumptions
        val schema = world.schema
        val containerField = schema.requireObjectField("Query", "container")
        val itemsKey =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("Container", "items"),
                emptyMap(),
            )
        val containerOmittedKey =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("Container", "omitted"),
                emptyMap(),
            )
        val valueKey =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("Item", "value"),
                emptyMap(),
            )
        val itemOmittedKey =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("Item", "omitted"),
                emptyMap(),
            )
        val output =
            schema.objectOf("Container") {
                "items" setTo
                    listOf(
                        objectOf("Item") { "value" setTo "one" },
                        objectOf("Item") { "value" setTo "two" },
                    )
            }
        val invocationDemand =
            world.fragmentFrom(
                "fragment ignored on Container { items { value } }",
            ).subselections

        val result =
            assertIs<ObjectEngineResult>(
                resolvePassiveValues(
                    world = world,
                    value = output,
                    expectedType = containerField.outputType,
                    path = listOf(containerField.key()),
                    invocationDemand = invocationDemand,
                    constructionDemand = selectionForestOf(),
                ),
            )

        assertFailsWith<NoSuchElementException> {
            result.reserveCell(containerOmittedKey)
        }
        val items =
            assertIs<ListEngineResult>(
                result.getCell(itemsKey).getValue().get(),
            )
        assertEquals(listOf("one", "two"), items.map { cell ->
            val item = assertIs<ObjectEngineResult>(cell.getValue().get())
            assertFailsWith<NoSuchElementException> {
                item.reserveCell(itemOmittedKey)
            }
            item.getCell(valueKey).getValue().get()
        })
    }

    @Test
    fun `projection materializes passive values while construction launches active values`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      item: Item!
                    }

                    type Item {
                      raw: String!
                      computed: String!
                      omitted: String
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Item", "computed") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Item"),
                            ) { _, _ -> "computed" },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val itemField = schema.requireObjectField("Query", "item")
        val rawKey =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("Item", "raw"),
                emptyMap(),
            )
        val computedKey =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("Item", "computed"),
                emptyMap(),
            )
        val omittedKey =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("Item", "omitted"),
                emptyMap(),
            )
        val output =
            schema.objectOf("Item") {
                "raw" setTo "raw"
            }
        val invocationDemand =
            world.fragmentFrom(
                "fragment ignored on Item { raw }",
            ).subselections
        val constructionDemand =
            world.fragmentFrom(
                "fragment ignored on Item { computed }",
            ).subselections

        val result =
            assertIs<ObjectEngineResult>(
                resolvePassiveValues(
                    world = world,
                    value = output,
                    expectedType = itemField.outputType,
                    path = listOf(itemField.key()),
                    invocationDemand = invocationDemand,
                    constructionDemand = constructionDemand,
                ),
            )

        assertEquals("raw", result.getCell(rawKey).getValue().get())
        assertEquals("computed", result.getCell(computedKey).getValue().get())
        assertFailsWith<NoSuchElementException> {
            result.reserveCell(omittedKey)
        }
    }

    @Test
    fun `source-provided active field coalesces equal invocation and construction keys`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      item: Item!
                    }

                    type Item {
                      computed: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Item", "computed") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Item"),
                            ) { _, _ -> error("standard resolver must not run") },
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val itemField = schema.requireObjectField("Query", "item")
        val itemKey = itemField.key()
        val computedField = schema.requireObjectField("Item", "computed")
        val computedKey =
            ObjectEngineResult.GroundKey.of(
                field = computedField,
                arguments = Arguments.Resolved.of(computedField, emptyMap()),
            )
        val demand =
            selectionForestOf(
                Selection.of(
                    key = computedKey,
                    possibleTypes = setOf(schema.requireType("Item") as ViaductSchema.Object),
                    subselections = selectionForestOf(),
                ),
            )
        val output =
            schema.objectOf("Item") {
                "computed" setTo "ancestor"
            }

        val result =
            assertIs<ObjectEngineResult>(
                resolvePassiveValues(
                    world = world,
                    value = output,
                    expectedType = itemField.outputType,
                    path = listOf(itemKey),
                    invocationDemand = demand,
                    constructionDemand = demand,
                ),
            )

        assertEquals(1, result.keys.size)
        assertEquals(computedKey, result.keys.single { key -> key == computedKey })
        result.keys.forEach { key ->
            assertEquals("ancestor", result.getCell(key).getValue().get())
        }
    }

    private fun ViaductSchema.ObjectField.key(): ObjectEngineResult.GroundKey =
        ObjectEngineResult.GroundKey.of(this, emptyMap())

    private fun resolvePassiveValues(
        world: Assumptions,
        value: EngineOutputData?,
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        path: List<PathComponent>,
        invocationDemand: SelectionForest,
        constructionDemand: SelectionForest,
    ): EngineResult? =
        runBlocking(resolverDispatcher) {
            coroutineScope {
                val baseOperation = SharedOperationContext.create(world)
                val operation =
                    OperationContext.create(
                        base = baseOperation,
                        requestScope = this,
                    )
                operation.passiveValues.resolvePassiveValues(
                    value = value,
                    root =
                        ObjectEngineResult.of(
                            world.schema.requireQueryTypeDef(),
                            values = emptyMap(),
                        ),
                    expectedType = expectedType,
                    path = path,
                    invocationDemand = invocationDemand,
                    constructionDemand = semantics.shared.Demand.checked(constructionDemand),
                )
            }
        }
}
