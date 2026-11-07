@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineOutputData
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.sameCompletedResultAs
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.model.testing.snipToDemand
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

class PassiveValueResolutionLogicTest : ResolutionDispatcherResource {
    @Test
    fun `undemanded unknown and argument-bearing fields are ignored at every object depth`() {
        val fixture = TestWorld.fromSDL("type Query { item: Item } type Item { selected: String child: Item extra(arg: Int): String }")
        val itemType = fixture.schemas.graphQLSchema.getObjectType("Item")
        val itemField = fixture.schema.requireObjectField("Query", "item")
        val demand = fixture.schemas.fragmentFrom("fragment Demand on Item { selected child { selected } }").subselections
        for (surplusField in listOf("unknown", "extra")) {
            for (nested in listOf(false, true)) {
                fun source(fields: Map<String, Any?>): EngineObjectData.Sync {
                    val output = ResolvedEngineObjectData(itemType, fields)
                    return object : EngineObjectData.Sync by output {
                        override fun get(selection: String): Any? {
                            check(selection != surplusField) { "Undemanded field must not be read" }
                            return output.get(selection)
                        }
                    }
                }
                val extra = mapOf(surplusField to "ignored")
                val child = source(mapOf("selected" to "nested") + if (nested) extra else emptyMap())
                val output = source(mapOf("selected" to "root", "child" to child) + if (nested) emptyMap() else extra)

                val result = assertIs<ObjectEngineResult>(
                    resolvePassiveValues(fixture.assumptions, output, itemField.outputType, listOf(itemField.key()), demand, demand)
                )

                assertEquals(setOf("selected", "child"), result.keys.map { it.field.name }.toSet())
                assertEquals("root", result.getCell(fixture.schema.requireObjectField("Item", "selected").key()).value.get())
                val resolvedChild = assertIs<ObjectEngineResult>(result.getCell(fixture.schema.requireObjectField("Item", "child").key()).value.get())
                assertEquals(setOf("selected"), resolvedChild.keys.map { it.field.name }.toSet())
                assertEquals("nested", resolvedChild.getCell(resolvedChild.keys.single()).value.get())
            }
        }
    }

    @Test
    fun `demanded argument-bearing passive output is rejected`() {
        val fixture = TestWorld.fromSDL(
            schemaSDL = "type Query { item: Item } type Item { extra(arg: Int): String }",
            fieldResolvers = { schemas ->
                mapOf(schemas.loweredSchema.requireObjectField("Item", "extra") to fieldResolverOf(schemas.loweredSchema.emptyFragmentOf("Item")) { _, _ -> "active" })
            },
        )
        val itemField = fixture.schema.requireObjectField("Query", "item")
        val output = ResolvedEngineObjectData(fixture.schemas.graphQLSchema.getObjectType("Item"), mapOf("extra" to "invalid"))
        val demand = fixture.schemas.fragmentFrom("fragment Demand on Item { extra(arg: 1) }").subselections

        val failure = assertFailsWith<IllegalArgumentException> {
            resolvePassiveValues(fixture.assumptions, output, itemField.outputType, listOf(itemField.key()), demand, demand)
        }

        assertTrue(failure.message.orEmpty().contains("argument-bearing field Item/extra"))
    }

    @Test
    fun `production skip agrees with projection for abstract lists errors and conditional references`() {
        val referenceCalls = AtomicInteger()
        val fixture = TestWorld.fromSDL(
            schemaSDL = """
                type Query { container: Container lookup: User }
                interface Named { name: String }
                type User implements Named { name: String userOnly: String ignored: String }
                type Team implements Named { name: String teamOnly: Int ignored: String }
                union Search = User | Team
                type Container {
                  named: [Named]
                  search: [Search]
                  matrix: [[Search]]
                  references: [Named]
                  ignoredReferences: [Named]
                  ignored: String
                }
            """.trimIndent(),
            fieldResolvers = { schemas ->
                mapOf(
                    schemas.loweredSchema.requireObjectField("Query", "lookup") to fieldResolverOf(schemas.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                        referenceCalls.incrementAndGet()
                        ResolvedEngineObjectData(schemas.graphQLSchema.getObjectType("User"), mapOf("name" to "reference", "ignored" to "surplus"))
                    },
                )
            },
        )
        val schema = fixture.schema
        val containerField = schema.requireObjectField("Query", "container")
        val user = ResolvedEngineObjectData(fixture.schemas.graphQLSchema.getObjectType("User"), mapOf("name" to "Ada", "userOnly" to "user", "ignored" to "surplus"))
        val team = ResolvedEngineObjectData(fixture.schemas.graphQLSchema.getObjectType("Team"), mapOf("name" to "Ductworks", "teamOnly" to 7, "ignored" to "surplus"))
        val error = EngineErrorData.of(IllegalStateException("list element"))
        val reference = RootFieldReferenceData.of(listOf(schema.requireObjectField("Query", "lookup")), emptyMap())
        val output = ResolvedEngineObjectData(
            fixture.schemas.graphQLSchema.getObjectType("Container"),
            mapOf(
                "named" to listOf(user, error, null, team),
                "search" to listOf(team, user, null, error),
                "matrix" to listOf(listOf(user, team), null, listOf(error)),
                "references" to listOf(reference, null, error),
                "ignoredReferences" to listOf(reference),
                "ignored" to "surplus",
            ),
        )
        for (includeReferences in listOf(false, true)) {
            referenceCalls.set(0)
            val demand = fixture.schemas.fragmentFrom(
                """
                fragment Demand on Container {
                  named { name ... on User { userOnly } ... on Team { teamOnly } }
                  search { ... on User { userOnly } ... on Team { teamOnly } }
                  matrix { ... on User { userOnly } ... on Team { teamOnly } }
                  references @include(if: $includeReferences) { name }
                  ignoredReferences @skip(if: true) { name }
                }
                """.trimIndent(),
            ).subselections

            fun resolve(value: EngineObjectData.Sync) =
                assertIs<ObjectEngineResult>(
                    resolvePassiveValues(fixture.assumptions, value, containerField.outputType, listOf(containerField.key()), demand, demand)
                )
            val complete = resolve(output)
            val projected = resolve(output.snipToDemand(demand, containerField.outputType.baseTypeDef))

            assertTrue(complete.sameCompletedResultAs(projected))
            assertEquals(if (includeReferences) 2 else 0, referenceCalls.get())
            val named = assertIs<ListEngineResult>(complete.getCell(schema.requireObjectField("Container", "named").key()).value.get())
            val resolvedUser = assertIs<ObjectEngineResult>(named[0].value.get())
            val resolvedTeam = assertIs<ObjectEngineResult>(named[3].value.get())
            assertEquals(setOf("name", "userOnly"), resolvedUser.keys.map { it.field.name }.toSet())
            assertEquals(setOf("name", "teamOnly"), resolvedTeam.keys.map { it.field.name }.toSet())
            assertSame(error, assertIs<ErrorEngineResult>(named[1].value.get()).errorData)
            assertEquals(null, named[2].value.get())
            assertTrue(schema.requireObjectField("Container", "ignoredReferences").key() !in complete.keys)
            assertTrue(schema.requireObjectField("Container", "ignored").key() !in complete.keys)
        }
    }

    @Test
    fun `resolved source values nulls and errors suppress resolvers while absent fields resolve`() {
        val calls = AtomicInteger()
        val fixture = TestWorld.fromSDL(
            schemaSDL = "type Query { item: Item } type Item { name: String nickname: String age: Int computed: String }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                val passive = listOf("name", "nickname", "age").associate { name ->
                    schema.requireObjectField("Item", name) to fieldResolverOf(schema.emptyFragmentOf("Item")) { _, _ ->
                        error("Source-supplied fields must suppress their standard resolvers")
                    }
                }
                passive + (
                    schema.requireObjectField("Item", "computed") to fieldResolverOf(
                        schemas.fragmentFrom("fragment Input on Item { name }"),
                    ) { input, _ ->
                        calls.incrementAndGet()
                        "computed:${input.get("name")}"
                    }
                )
            },
        )
        val schema = fixture.schema
        val itemField = schema.requireObjectField("Query", "item")
        val error = EngineErrorData.of(IllegalStateException("field failure"))
        val source = ResolvedEngineObjectData(fixture.schemas.graphQLSchema.getObjectType("Item"), mapOf("name" to "Ada", "nickname" to null, "age" to error))
        val demand = fixture.schemas.fragmentFrom("fragment Demand on Item { name nickname age computed }").subselections

        val result = assertIs<ObjectEngineResult>(
            resolvePassiveValues(
                world = fixture.assumptions,
                value = source,
                expectedType = itemField.outputType,
                path = listOf(itemField.key()),
                invocationDemand = demand,
                constructionDemand = demand,
            )
        )

        assertEquals("Ada", result.getCell(schema.requireObjectField("Item", "name").key()).value.get())
        assertEquals(null, result.getCell(schema.requireObjectField("Item", "nickname").key()).value.get())
        assertSame(error, assertIs<ErrorEngineResult>(result.getCell(schema.requireObjectField("Item", "age").key()).value.get()).errorData)
        assertEquals("computed:Ada", result.getCell(schema.requireObjectField("Item", "computed").key()).value.get())
        assertEquals(1, calls.get())
    }

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
