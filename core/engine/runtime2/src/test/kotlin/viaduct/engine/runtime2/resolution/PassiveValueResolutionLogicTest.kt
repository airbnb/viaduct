@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Assumptions
import viaduct.engine.runtime2.model.EngineOutputData
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

class PassiveValueResolutionLogicTest : ResolutionDispatcherResource {
    @Test
    fun `passive-only object trees freeze synchronously as they are created`() {
        val worldFixture = TestWorld
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
            )
        val world = worldFixture.assumptions
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
            worldFixture.schemas.fragmentFrom(
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
                result.getCell(itemsKey).value.get(),
            )
        assertEquals(
            listOf("one", "two"),
            items.map { cell ->
                val item = assertIs<ObjectEngineResult>(cell.value.get())
                assertFailsWith<NoSuchElementException> {
                    item.reserveCell(itemOmittedKey)
                }
                item.getCell(valueKey).value.get()
            }
        )
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
                        schema.loweredSchema.requireObjectField("Item", "computed") to
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Item"),
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
            testWorld.schemas.fragmentFrom(
                "fragment ignored on Item { raw }",
            ).subselections
        val constructionDemand =
            testWorld.schemas.fragmentFrom(
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

        assertEquals("raw", result.getCell(rawKey).value.get())
        assertEquals("computed", result.getCell(computedKey).value.get())
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
                        schema.loweredSchema.requireObjectField("Item", "computed") to
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Item"),
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
            assertEquals("ancestor", result.getCell(key).value.get())
        }
    }

    private fun ViaductSchema.ObjectField.key(): ObjectEngineResult.GroundKey = ObjectEngineResult.GroundKey.of(this, emptyMap())

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
                    constructionDemand = viaduct.engine.runtime2.resolution.framework.Demand.checked(constructionDemand),
                )
            }
        }
}
