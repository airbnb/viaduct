package viaduct.engine.runtime2.execution

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockNodeUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.execution.query
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime2.execution.testing.fixtureFieldExecutor
import viaduct.engine.runtime2.execution.testing.fixtureNodeExecutor
import viaduct.engine.runtime2.execution.testing.runQPlanFeatureTest
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.graphql.schema.ViaductSchema

class EngineTestModuleQPlanFeatureTest {
    @Test
    fun `field wrapper adapts nested mock maps objects and scalars before production`() =
        runTest {
            val module = EngineTestModule(
                """
                extend type Query { users: [User] }
                type User { id: ID age: Int scores: [Int] child: Detail name: String missing: String }
                type Detail { weight: Float }
                """.trimIndent(),
            ) {}
            val userType = module.fullSchema.schema.getObjectType("User")
            val detailType = module.fullSchema.schema.getObjectType("Detail")
            val child = object : EngineObjectData.Sync by ResolvedEngineObjectData(detailType, mapOf("weight" to 8)) {}
            val legacy = object : EngineObjectData.Sync by ResolvedEngineObjectData(userType, mapOf("age" to 9L)) {}
            val executor = MockFieldUnbatchedResolverExecutor(resolverId = "Query.users") { _, _, _, _, _ ->
                listOf(mapOf("id" to 42, "age" to 7L, "scores" to listOf(3L, null), "child" to child, "name" to null), legacy)
            }
            val root = ResolvedEngineObjectData(module.fullSchema.schema.queryType, emptyMap())
            val selector = FieldResolverExecutor.Selector(emptyMap(), null, { root }, { root })
            val context = ContextMocks(myFullSchema = module.fullSchema).engineExecutionContext

            val output = module.fixtureFieldExecutor("Query" to "users", executor)
                .batchResolve(listOf(selector), context).getValue(selector).getOrThrow() as List<*>

            val first = assertIs<ResolvedEngineObjectData>(output[0])
            assertEquals("42", first.get("id"))
            assertEquals(7, first.get("age"))
            assertEquals(listOf(3, null), first.get("scores"))
            assertEquals(8.0, assertIs<ResolvedEngineObjectData>(first.get("child")).get("weight"))
            assertTrue(first.isPresent("name"))
            assertNull(first.get("name"))
            assertFalse(first.isPresent("missing"))
            assertEquals(9, assertIs<ResolvedEngineObjectData>(output[1]).get("age"))
        }

    @Test
    fun `node wrapper adapts scalar values and supplies only missing demanded nullable fields`() =
        runTest {
            val module = EngineTestModule(
                """
                type User implements Node { id: ID! age: Int nickname: String unused: String active: String @resolver }
                """.trimIndent(),
            ) {
                fieldWithValue("User" to "active", "owned")
            }
            val userType = module.fullSchema.schema.getObjectType("User")
            val legacy = object : EngineObjectData.Sync by ResolvedEngineObjectData(userType, mapOf("age" to 7L)) {}
            val executor = MockNodeUnbatchedResolverExecutor(typeName = "User", isSelective = true) { _, _, _ -> legacy }
            val context = ContextMocks(myFullSchema = module.fullSchema).engineExecutionContext
            val selections = context.engineSelectionSetFactory.engineSelectionSet("User", "id age nickname active", emptyMap())
            val selector = NodeResolverExecutor.Selector("u1", selections)

            val output = module.fixtureNodeExecutor("User", executor)
                .resolve(listOf(selector), context).getValue(selector).getOrThrow()

            val data = assertIs<ResolvedEngineObjectData>(output)
            assertEquals(7, data.get("age"))
            assertEquals("__qplan_inline_node__", data.get("id"))
            assertTrue(data.isPresent("nickname"))
            assertNull(data.get("nickname"))
            assertFalse(data.isPresent("unused"))
            assertFalse(data.isPresent("active"))
        }

    @Test
    fun `fixture wrappers preserve runtime2 object and stored error identity`() =
        runTest {
            val module = EngineTestModule("extend type Query { user: User } type User implements Node { id: ID! age: Int }") {}
            val schemas = ViaductAndGJSchema.fromGraphQLSchema(module.fullSchema.schema)
            val userType = schemas.loweredSchema.requireType("User") as ViaductSchema.Object
            val marker = EngineErrorData.of()
            val prepared = engineObjectDataOf(userType, mapOf("age" to marker))
            val executor = MockFieldUnbatchedResolverExecutor(resolverId = "Query.user") { _, _, _, _, _ -> prepared }
            val root = ResolvedEngineObjectData(module.fullSchema.schema.queryType, emptyMap())
            val selector = FieldResolverExecutor.Selector(emptyMap(), null, { root }, { root })
            val context = ContextMocks(myFullSchema = module.fullSchema).engineExecutionContext

            val output = module.fixtureFieldExecutor("Query" to "user", executor)
                .batchResolve(listOf(selector), context).getValue(selector).getOrThrow()

            assertSame(prepared, output)
            assertSame(marker, prepared.outputValue("age"))

            val nodeExecutor = MockNodeUnbatchedResolverExecutor(typeName = "User") { _, _, _ -> prepared }
            val selections = context.engineSelectionSetFactory.engineSelectionSet("User", "age", emptyMap())
            val nodeSelector = NodeResolverExecutor.Selector("u1", selections)
            val nodeOutput = module.fixtureNodeExecutor("User", nodeExecutor)
                .resolve(listOf(nodeSelector), context).getValue(nodeSelector).getOrThrow()

            assertSame(prepared, nodeOutput)
        }

    @Test
    fun `field wrapper preserves failed executor results`() =
        runTest {
            val module = EngineTestModule("extend type Query { value: Int }") {}
            val failure = IllegalStateException("resolver failed")
            val executor = MockFieldUnbatchedResolverExecutor(resolverId = "Query.value") { _, _, _, _, _ -> throw failure }
            val root = ResolvedEngineObjectData(module.fullSchema.schema.queryType, emptyMap())
            val selector = FieldResolverExecutor.Selector(emptyMap(), null, { root }, { root })
            val context = ContextMocks(myFullSchema = module.fullSchema).engineExecutionContext

            val result = module.fixtureFieldExecutor("Query" to "value", executor)
                .batchResolve(listOf(selector), context).getValue(selector)

            assertSame(failure, result.exceptionOrNull())
        }

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
    fun `adapts batching executors through the production immediate dispatcher`() {
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
                        MockFieldBatchResolverExecutor(
                            resolverId = resolverId,
                            batchResolveFn = { selectors, _ ->
                                selectors.associateWith { Result.success(7) }
                            },
                        )
                    }
                }
            }

        module.runQPlanFeatureTest {
            runQuery("{ value }").assertJson("{data: {value: 7}}")
        }
    }
}
