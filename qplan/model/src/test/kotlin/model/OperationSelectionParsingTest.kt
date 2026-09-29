package model

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.OperationDefinition
import graphql.parser.Parser
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import model.testing.TestWorld
import viaduct.graphql.schema.ViaductSchema

class OperationSelectionParsingTest {
    @Test
    fun `decodes literals and coerced operation variables to ground keys`() {
        val fixture = Fixture(ARGUMENT_SCHEMA)
        val selections =
            fixture.decode(
                """
                query Search(${'$'}term: String!, ${'$'}limit: Int = 5) {
                  byVariable: search(term: ${'$'}term, limit: ${'$'}limit)
                  byLiteral: search(term: "literal", limit: 7, sort: DESC)
                }
                """.trimIndent(),
                variables = mapOf("term" to "variable"),
            )

        val arguments =
            selections
                .merge(fixture.schema.requireQueryTypeDef())
                .groundKeys()
                .mapTo(linkedSetOf()) { key -> key.arguments.fieldExpressions() }
        val expected: Set<Map<String, Any?>> =
            setOf(
                mapOf(
                    "term" to "variable",
                    "limit" to 5,
                    "sort" to "ASC",
                    "filter" to mapOf("enabled" to true, "tags" to listOf("all")),
                ),
                mapOf(
                    "term" to "literal",
                    "limit" to 7,
                    "sort" to "DESC",
                    "filter" to mapOf("enabled" to true, "tags" to listOf("all")),
                ),
            )

        assertEquals(expected, arguments)
    }

    @Test
    fun `applies field defaults when an optional operation variable is absent`() {
        val fixture = Fixture(ARGUMENT_SCHEMA)
        val selections =
            fixture.decode(
                """
                query Search(${'$'}term: String) {
                  search(term: ${'$'}term)
                }
                """.trimIndent(),
            )

        val key = selections.merge(fixture.schema.requireQueryTypeDef()).groundKeys().single()
        assertEquals(
            mapOf(
                "term" to "fallback",
                "limit" to 3,
                "sort" to "ASC",
                "filter" to mapOf("enabled" to true, "tags" to listOf("all")),
            ),
            key.arguments.fieldExpressions(),
        )
    }

    @Test
    fun `reuses inline fragments for Node operations`() {
        val fixture = Fixture(NODE_SCHEMA)
        val selections =
            fixture.decode(
                """
                query Node(${'$'}id: ID!) {
                  account: node(id: ${'$'}id) {
                    id
                    ... on User {
                      handle
                    }
                  }
                }
                """.trimIndent(),
                variables = mapOf("id" to 42),
            )

        val root = selections.merge(fixture.schema.requireQueryTypeDef()).single()
        assertEquals("node", root.key.field.name)
        assertEquals(
            mapOf("id" to "42"),
            root.key.arguments.fieldExpressions(),
        )

        val userType = fixture.schema.requireType("User") as ViaductSchema.Object
        assertEquals(
            setOf("id", "handle"),
            root.subselections
                .merge(userType)
                .groundKeys()
                .mapTo(linkedSetOf()) { key -> key.field.name },
        )
    }

    @Test
    fun `grounds operation field and fragment-spread inclusion directives`() {
        val fixture = Fixture(ARGUMENT_SCHEMA)
        val included =
            fixture.decode(
                "query(${'$'}show: Boolean!) { search @include(if: ${'$'}show) }",
                variables = mapOf("show" to true),
            )
        val skipped =
            fixture.decode(
                """
                query(${'$'}skip: Boolean!) {
                  ...Search @skip(if: ${'$'}skip)
                }

                fragment Search on Query {
                  search
                }
                """.trimIndent(),
                variables = mapOf("skip" to true),
            )
        val conflicting =
            fixture.decode(
                "query { search @include(if: true) @skip(if: true) }",
            )

        assertSame(InclusionCondition.Always, included.single().inclusionCondition)
        assertSame(InclusionCondition.Never, skipped.single().inclusionCondition)
        assertSame(InclusionCondition.Never, conflicting.single().inclusionCondition)
    }

    @Test
    fun `rejects unsupported operation forms`() {
        val fixture = Fixture(ARGUMENT_SCHEMA)

        assertFailsWith<IllegalArgumentException> {
            fixture.decodeUnvalidated(
                """
                mutation {
                  search
                }
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            fixture.decodeUnvalidated(
                """
                query {
                  ...Search
                }

                fragment Search on Query {
                  search
                }
                """.trimIndent(),
            )
        }
    }

    private class Fixture(
        schemaSDL: String,
    ) {
        private val world = TestWorld.fromSDL(schemaSDL)
        val schema = world.schema
        private val graphQLContext = GraphQLContext.getDefault()
        private val locale = Locale.ENGLISH

        fun decode(
            operationText: String,
            variables: Map<String, Any?> = emptyMap(),
        ): SelectionForest =
            world.assumptions.operationSelectionsFrom(
                documentSource = operationText,
                variables = variables,
                graphQLContext = graphQLContext,
                locale = locale,
            )

        fun decodeUnvalidated(operationText: String): SelectionForest {
            val document = Parser.parse(operationText)
            val operation = document.getDefinitionsOfType(OperationDefinition::class.java).single()
            return world.assumptions.selectionsFrom(
                operation = operation,
                variables = CoercedVariables.emptyVariables(),
                graphQLContext = graphQLContext,
                locale = locale,
            )
        }
    }

    private companion object {
        val ARGUMENT_SCHEMA =
            """
            enum Sort {
              ASC
              DESC
            }

            input Filter {
              enabled: Boolean = true
              tags: [String!]! = ["all"]
            }

            type Query {
              search(
                term: String = "fallback"
                limit: Int = 3
                sort: Sort = ASC
                filter: Filter = {}
              ): String
            }
            """.trimIndent()

        val NODE_SCHEMA =
            """
            interface Node {
              id: ID!
            }

            type User implements Node {
              id: ID!
              handle: String!
            }

            type Query {
              node(id: ID!): Node
            }
            """.trimIndent()
    }
}
