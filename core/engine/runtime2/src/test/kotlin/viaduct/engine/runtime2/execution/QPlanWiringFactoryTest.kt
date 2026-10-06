package viaduct.engine.runtime2.execution

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.runtime2.execution.testing.ExecutionTestFixture
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Promise
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.engineResultOf
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.graphql.schema.ViaductSchema

class QPlanWiringFactoryTest {
    @Test
    fun `wiring completes values using only main-source schema and result APIs`() {
        val registry = SchemaParser().parse("type Query { greeting(prefix: String!): String! }")
        val source = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
        val schema = ViaductAndGJSchema.fromGraphQLSchema(source).loweredSchema
        val key =
            ObjectEngineResult.GroundKey.of(
                field = schema.requireObjectField("Query", "greeting"),
                arguments = mapOf("prefix" to "hello"),
            )
        val root =
            ObjectEngineResult.of(
                type = schema.requireQueryTypeDef(),
                values = mapOf(key to "hello world"),
            )
        val executableSchema =
            SchemaGenerator().makeExecutableSchema(
                registry,
                RuntimeWiring.newRuntimeWiring().wiringFactory(QPlanWiringFactory(schema)).build(),
            )

        val result =
            GraphQL.newGraphQL(executableSchema).build().execute(
                ExecutionInput.newExecutionInput()
                    .query("query(${'$'}prefix: String!) { message: greeting(prefix: ${'$'}prefix) }")
                    .variables(mapOf("prefix" to "hello"))
                    .root(root)
                    .build(),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(mapOf("message" to "hello world"), result.getData())
    }

    @Test
    fun `vanilla GraphQL execution completes a resolved OER tree`() {
        val world = TestWorld.fromSDL(SCHEMA).assumptions
        val friend =
            world.engineResultOf("User") {
                "id" resolvesTo "user-2"
                "role" resolvesTo "MEMBER"
                "tags" resolvesTo emptyList<String>()
                "friends" resolvesTo emptyList<Any>()
            }
        val user =
            world.engineResultOf("User") {
                "id".resolvesTo("user-1", fieldCheckerResult = CheckerResult.Success)
                "role" resolvesTo "ADMIN"
                "tags" resolvesTo listOf("engineer", null)
                "friends" resolvesTo listOf(friend)
            }
        val root =
            world.engineResultOf("Query") {
                field("user", "id" to "user-1").resolvesTo(
                    value = user,
                    fieldCheckerResult = CheckerResult.Success,
                )
            }
        val fixture =
            ExecutionTestFixture.fromResolvedRoot(
                schemaSDL = SCHEMA,
                schema = world.schema,
                root = root,
            )

        val result =
            fixture.runQuery(
                """
                query User(${'$'}id: ID!) {
                  account: user(id: ${'$'}id) {
                    id
                    role
                    tags
                    friends {
                      id
                      role
                    }
                  }
                }
                """.trimIndent(),
                variables = mapOf("id" to "user-1"),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf(
                "account" to
                    mapOf(
                        "id" to "user-1",
                        "role" to "ADMIN",
                        "tags" to listOf("engineer", null),
                        "friends" to
                            listOf(
                                mapOf(
                                    "id" to "user-2",
                                    "role" to "MEMBER",
                                ),
                            ),
                    ),
            ),
            result.getData(),
        )
    }

    @Test
    fun `GraphQL completion enforces field and type checker errors`() {
        val world = TestWorld.fromSDL(CHECKER_SCHEMA).assumptions
        val completionOnlyDenial = TestCheckerError("field denied", resolverError = false)
        val typeDenial = TestCheckerError("type denied")
        val listDenial = TestCheckerError("list item denied")
        val protectedType = world.schema.requireType("Protected") as ViaductSchema.Object
        val textKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Protected", "text"),
                emptyMap(),
            )

        fun protected(
            text: String,
            typeCheckerResult: CheckerResult?,
        ): ObjectEngineResult =
            ObjectEngineResult.of(
                type = protectedType,
                typeCheckerResult = Promise.of(typeCheckerResult),
                values = mapOf(textKey to text),
            )
        val listField = world.schema.requireObjectField("Query", "listTypeDenied")
        val list =
            ListEngineResult.of(
                typeExpr = listField.outputType.unwrapList()!!,
                values = listOf(protected("visible", null), protected("secret", listDenial)),
            )
        val root =
            world.engineResultOf("Query") {
                "fieldDenied".resolvesTo("secret", completionOnlyDenial)
                "typeDenied" resolvesTo protected("secret", typeDenial)
                "listTypeDenied" resolvesTo list
            }
        val fixture =
            ExecutionTestFixture.fromResolvedRoot(
                schemaSDL = CHECKER_SCHEMA,
                schema = world.schema,
                root = root,
            )

        val result = fixture.runQuery("{ fieldDenied typeDenied { text } listTypeDenied { text } }")

        assertEquals(
            mapOf(
                "fieldDenied" to null,
                "typeDenied" to null,
                "listTypeDenied" to listOf(mapOf("text" to "visible"), null),
            ),
            result.getData(),
        )
        assertEquals(
            setOf(
                listOf("fieldDenied"),
                listOf("typeDenied"),
                listOf("listTypeDenied", 1),
            ),
            result.errors.map { error -> error.path }.toSet(),
        )
    }

    @Test
    fun `completion denial does not wait for the raw value`() {
        val world = TestWorld.fromSDL("type Query { value: String }").assumptions
        val key =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "value"),
                emptyMap(),
            )
        val denial = TestCheckerError("completion denied")
        val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
        val cell = root.reserveCell(key)
        cell.value
        cell.setActivated(true)
        cell.fieldCheckerResult.complete(denial)
        val fixture =
            ExecutionTestFixture.fromResolvedRoot(
                schemaSDL = "type Query { value: String }",
                schema = world.schema,
                root = root,
            )

        val result = fixture.runQuery("{ value }")

        assertEquals(mapOf("value" to null), result.getData())
        assertEquals(listOf("value"), result.errors.single().path)
        assertTrue(result.errors.single().message.contains("completion denied"))
        assertFalse(cell.value.isCompleted)
    }

    @Test
    fun `source Node fields complete directly from resolved node results`() {
        val world = TestWorld.fromSDL(NODE_SCHEMA).assumptions
        val user =
            world.engineResultOf("User") {
                "id" resolvesTo "user-1"
                "name" resolvesTo "Ada"
            }
        val root =
            world.engineResultOf("Query") {
                field("node", "id" to "user-1") resolvesTo user
            }
        val fixture =
            ExecutionTestFixture.fromResolvedRoot(
                schemaSDL = NODE_SCHEMA,
                schema = world.schema,
                root = root,
            )

        val result =
            fixture.runQuery(
                """
                query {
                  node(id: "user-1") {
                    id
                    ... on User {
                      name
                    }
                  }
                }
                """.trimIndent(),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf(
                "node" to
                    mapOf(
                        "id" to "user-1",
                        "name" to "Ada",
                    ),
            ),
            result.getData(),
        )
    }

    private companion object {
        val SCHEMA =
            """
            enum Role {
              ADMIN
              MEMBER
            }

            type Query {
              user(id: ID!, greeting: String = "hello"): User!
            }

            type User {
              id: ID!
              role: Role!
              tags: [String]
              friends: [User!]!
            }
            """.trimIndent()

        val NODE_SCHEMA =
            """
            interface Node {
              id: ID!
            }

            type User implements Node {
              id: ID!
              name: String!
            }

            type Query {
              node(id: ID!): Node
            }
            """.trimIndent()

        val CHECKER_SCHEMA =
            """
            type Query {
              fieldDenied: String
              typeDenied: Protected
              listTypeDenied: [Protected]
            }

            type Protected {
              text: String
            }
            """.trimIndent()
    }
}

private class TestCheckerError(
    message: String,
    private val resolverError: Boolean = true,
) : CheckerResult.Error {
    override val error: Exception = SecurityException(message)

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = resolverError

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
