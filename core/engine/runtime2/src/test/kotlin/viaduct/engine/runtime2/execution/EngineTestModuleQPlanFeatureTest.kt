package viaduct.engine.runtime2.execution

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.runtime.execution.query
import viaduct.engine.runtime2.execution.testing.runQPlanFeatureTest

class EngineTestModuleQPlanFeatureTest {
    @Test
    fun `ctx query nests under the calling field task`() {
        EngineTestModule(
            """
            extend type Query {
              base: Int!
              middle: Int!
              outer: Int!
            }
            """.trimIndent(),
        ) {
            fieldWithValue("Query" to "base", 2)
            field("Query" to "middle") {
                resolver {
                    fn { _, _, _, _, context ->
                        val selections = context.engineSelectionSetFactory
                            .engineSelectionSet("Query", "base", emptyMap())
                        context.query(selections).fetchAs<Int>("base") * 3
                    }
                }
            }
            field("Query" to "outer") {
                resolver {
                    fn { _, _, _, _, context ->
                        val selections = context.engineSelectionSetFactory
                            .engineSelectionSet("Query", "middle", emptyMap())
                        context.query(selections).fetchAs<Int>("middle") + 1
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ outer }").assertJson("{data: {outer: 7}}")
        }
    }

    @Test
    fun `ctx query preserves aliases arguments and variables`() {
        EngineTestModule(
            """
            extend type Query {
              value(x: Int!): Int!
              result: Int!
            }
            """.trimIndent(),
        ) {
            field("Query" to "value") {
                resolver {
                    fn { arguments, _, _, _, _ -> arguments.getValue("x") }
                }
            }
            field("Query" to "result") {
                resolver {
                    fn { _, _, _, _, context ->
                        val selections = context.engineSelectionSetFactory.engineSelectionSet(
                            "Query",
                            "renamed: value(x: ${'$'}x)",
                            mapOf("x" to 41),
                        )
                        context.query(selections).fetchAs<Int>("renamed") + 1
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ result }").assertJson("{data: {result: 42}}")
        }
    }

    @Test
    fun `supplies empty objects for namespace fields`() {
        EngineTestModule(
            """
            type Catalog @namespaceType {
              value: Int! @resolver
            }

            extend type Query {
              catalog: Catalog
            }
            """.trimIndent(),
        ) {
            fieldWithValue("Catalog" to "value", 7)
        }.runQPlanFeatureTest {
            runQuery("{ catalog { value } }").assertJson("{data: {catalog: {value: 7}}}")
        }
    }

    @Test
    fun `normalizes root-field references nested in object lists`() {
        EngineTestModule(
            """
            type Product {
              name: String!
            }

            type ProductFactory @namespaceType {
              create(name: String!): Product! @resolver
            }

            type Shelf {
              product: Product
            }

            extend type Query {
              productFactory: ProductFactory
              shelves: [Shelf!]! @resolver
            }
            """.trimIndent(),
        ) {
            field("ProductFactory" to "create") {
                resolver {
                    fn { arguments, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Product"),
                            mapOf("name" to arguments.getValue("name")),
                        )
                    }
                }
            }
            field("Query" to "shelves") {
                resolver {
                    fn { _, _, _, _, context ->
                        listOf(
                            createEngineObjectData(
                                schema.schema.getObjectType("Shelf"),
                                mapOf(
                                    "product" to
                                        context.createRootFieldReference(
                                            rootFieldPath = listOf("productFactory", "create"),
                                            type = schema.schema.getObjectType("Product"),
                                            args = mapOf("name" to "chair"),
                                        ),
                                ),
                            ),
                        )
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ shelves { product { name } } }")
                .assertJson("""{"data": {"shelves": [{"product": {"name": "chair"}}]}}""")
        }
    }

    @Test
    fun `executes field executors with arguments and object required selections`() {
        EngineTestModule(
            """
            extend type Query {
              base: Int!
              total(extra: Int!): Int!
            }
            """.trimIndent(),
        ) {
            fieldWithValue("Query" to "base", 5)
            field("Query" to "total") {
                resolver {
                    objectSelections("base")
                    fn { args, objectValue, _, _, _ ->
                        objectValue.get("base") as Int + args.getValue("extra") as Int
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ total(extra: 4) }").assertJson("{data: {total: 9}}")
        }
    }

    @Test
    fun `executes field executors with Query required selections`() {
        EngineTestModule(
            """
            extend type Query {
              base: Int!
              total: Int!
            }
            """.trimIndent(),
        ) {
            fieldWithValue("Query" to "base", 5)
            field("Query" to "total") {
                resolver {
                    querySelections("base")
                    fn { _, _, queryValue, _, _ ->
                        queryValue.get("base") as Int + 4
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ total }").assertJson("{data: {total: 9}}")
        }
    }

    @Test
    fun `composes variable providers once per occurrence across both fragments`() {
        val factorCalls = AtomicInteger()
        val offsetCalls = AtomicInteger()
        EngineTestModule(
            """
            extend type Query {
              left(x: Int!): Int!
              right(x: Int!): Int!
              total(multiplier: Int!): Int!
            }
            """.trimIndent(),
        ) {
            field("Query" to "left") {
                resolver { fn { args, _, _, _, _ -> args.getValue("x") } }
            }
            field("Query" to "right") {
                resolver { fn { args, _, _, _, _ -> args.getValue("x") } }
            }
            field("Query" to "total") {
                resolver {
                    objectSelections("left(x: \$factor)") {
                        variables("factor") { variables, _ ->
                            factorCalls.incrementAndGet()
                            mapOf("factor" to (variables.arguments.getValue("multiplier") as Int) * 2)
                        }
                    }
                    querySelections("right(x: \$offset)") {
                        variables("offset") { variables, _ ->
                            offsetCalls.incrementAndGet()
                            mapOf("offset" to (variables.arguments.getValue("multiplier") as Int) + 1)
                        }
                    }
                    fn { _, objectValue, queryValue, _, _ ->
                        objectValue.get("left") as Int + queryValue.get("right") as Int
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ a: total(multiplier: 3), b: total(multiplier: 4) }")
                .assertJson("{data: {a: 10, b: 13}}")
        }

        assertEquals(2, factorCalls.get())
        assertEquals(2, offsetCalls.get())
    }

    @Test
    fun `passes qplan output demand to selective field executors`() {
        var requestedFields: Set<String>? = null
        EngineTestModule(
            """
            extend type Query { viewer: User! }
            type User { name: String!, age: Int! }
            """.trimIndent(),
        ) {
            field("Query" to "viewer") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            requestedFields =
                                requireNotNull(selections)
                                    .selections()
                                    .mapTo(linkedSetOf()) { it.fieldName }
                            createEngineObjectData(
                                requireNotNull(schema.schema.getObjectType("User")),
                                mapOf("name" to "Ada"),
                            )
                        },
                    )
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ viewer { name } }").assertJson("{data: {viewer: {name: \"Ada\"}}}")
        }

        assertEquals(setOf("name"), requestedFields)
    }

    @Test
    fun `supports named fragments in object required selections`() {
        EngineTestModule(
            """
            extend type Query {
              base: Int!
              total: Int!
            }
            """.trimIndent(),
        ) {
            fieldWithValue("Query" to "base", 5)
            field("Query" to "total") {
                resolver {
                    objectSelections(
                        """
                        fragment Base on Query { base }
                        fragment Main on Query { ...Base }
                        """.trimIndent(),
                    )
                    fn { _, objectValue, _, _, _ ->
                        objectValue.get("base") as Int
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ total }").assertJson("{data: {total: 5}}")
        }
    }

    @Test
    fun `completes typename through qplan lowering`() {
        EngineTestModule(
            """
            extend type Query {
              viewer: User!
            }

            type User {
              id: ID!
            }
            """.trimIndent(),
        ) {
            field("Query" to "viewer") {
                value(
                    createEngineObjectData(
                        graphQLObjectType = requireNotNull(schema.schema.getObjectType("User")),
                        data = mapOf("id" to "u1"),
                    ),
                )
            }
        }.runQPlanFeatureTest {
            val result = runQuery("{ viewer { __typename id } }")

            assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
            assertEquals(
                mapOf("viewer" to mapOf("__typename" to "User", "id" to "u1")),
                result.getData(),
            )
        }
    }

    @Test
    fun `lowers field node references and invokes node executors`() {
        val globalId = java.util.Base64.getEncoder().encodeToString("User:u1".toByteArray())
        EngineTestModule(
            """
            extend type Query {
              viewer: User!
            }

            type User implements Node {
              id: ID!
              name: String!
            }
            """.trimIndent(),
        ) {
            field("Query" to "viewer") {
                valueFromContext { context ->
                    context.createNodeReference(
                        globalId,
                        requireNotNull(schema.schema.getObjectType("User")),
                    )
                }
            }
            type("User") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf("id" to id, "name" to "Ada"),
                    )
                }
            }
        }.runQPlanFeatureTest {
            val result = runQuery("{ viewer { __typename id name } }")

            assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
            assertEquals(
                mapOf(
                    "viewer" to
                        mapOf(
                            "__typename" to "User",
                            "id" to globalId,
                            "name" to "Ada",
                        ),
                ),
                result.getData(),
            )
        }
    }

    @Test
    fun `supports built in Query node without dispatchers`() {
        val globalId = java.util.Base64.getEncoder().encodeToString("User:u1".toByteArray())
        EngineTestModule(
            """
            type User implements Node {
              id: ID!
              name: String!
            }
            """.trimIndent(),
        ) {
            type("User") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf("id" to id, "name" to "Grace"),
                    )
                }
            }
        }.runQPlanFeatureTest {
            val result =
                runQuery(
                    """
                    query Node(${'$'}id: ID!) {
                      node(id: ${'$'}id) {
                        __typename
                        id
                        ... on User { name }
                      }
                    }
                    """.trimIndent(),
                    mapOf("id" to globalId),
                )

            assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
            assertEquals(
                mapOf(
                    "node" to
                        mapOf(
                            "__typename" to "User",
                            "id" to globalId,
                            "name" to "Grace",
                        ),
                ),
                result.getData(),
            )
        }
    }

    @Test
    fun `built in Query nodes returns independently resolved node references`() {
        val firstId = java.util.Base64.getEncoder().encodeToString("User:u1".toByteArray())
        val secondId = java.util.Base64.getEncoder().encodeToString("User:u2".toByteArray())
        EngineTestModule(
            """
            type User implements Node {
              id: ID!
              name: String!
            }
            """.trimIndent(),
        ) {
            type("User") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf(
                            "id" to id,
                            "name" to if (id == firstId) "Ada" else "Grace",
                        ),
                    )
                }
            }
        }.runQPlanFeatureTest {
            val result =
                runQuery(
                    """
                    query Nodes(${'$'}ids: [ID!]!) {
                      nodes(ids: ${'$'}ids) {
                        id
                        ... on User { name }
                      }
                    }
                    """.trimIndent(),
                    mapOf("ids" to listOf(firstId, secondId)),
                )

            assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
            assertEquals(
                mapOf(
                    "nodes" to
                        listOf(
                            mapOf("id" to firstId, "name" to "Ada"),
                            mapOf("id" to secondId, "name" to "Grace"),
                        ),
                ),
                result.getData(),
            )
        }
    }

    @Test
    fun `rejects batching before constructing qplan`() {
        val module =
            EngineTestModule(
                """
                extend type Query {
                  value: Int
                }
                """.trimIndent(),
            ) {
                field("Query" to "value") {
                    resolverExecutor {
                        MockFieldBatchResolverExecutor(resolverId = resolverId)
                    }
                }
            }

        val error =
            assertFailsWith<NotImplementedError> {
                module.runQPlanFeatureTest {}
            }
        assertTrue(error.message.orEmpty().contains("batching field executor Query.value"))
    }
}
