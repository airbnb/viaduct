package model.registry

import kotlin.test.Test
import kotlin.test.assertFailsWith
import model.Arguments
import model.emptyFragmentOf
import model.fragmentFrom
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument

class ParentVariableValidationAdversarialTest {
    @Test
    fun `accepts unreachable variable below a statically excluded selection beneath parent`() {
        listOf(
            "parent { localized(locale: ${'$'}locale) @skip(if: true) }",
            "parent @skip(if: true) { localized(locale: ${'$'}locale) }",
        ).forEach { parentSelection ->
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { root: Root }
                    type Root {
                      child: Child
                      localized(locale: String!): String
                      result(locale: String!): String
                    }
                    type Child { parent: Root @parent }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    fun resolver(fragment: model.Fragment) = fieldResolverOf(fragment) { _, _ -> error("not invoked") }
                    val result = schema.loweredSchema.requireObjectField("Root", "result")
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "root") to
                            resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                        schema.loweredSchema.requireObjectField("Root", "child") to
                            resolver(schema.loweredSchema.emptyFragmentOf("Root")),
                        schema.loweredSchema.requireObjectField("Root", "localized") to
                            resolver(schema.loweredSchema.emptyFragmentOf("Root")),
                        result to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Root {
                                      child {
                                        $parentSelection
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                    )
                },
                variableProviders = { schema ->
                    val result = schema.loweredSchema.requireObjectField("Root", "result")
                    mapOf(
                        Arguments.Variable.of(result, "locale") to
                            schema.loweredSchema.fromArgument(result, "locale"),
                    )
                },
            )
        }
    }

    @Test
    fun `accepts variable restricted to non-parent concrete branch of mixed abstract field`() {
        TestWorld.fromDSL(
            """
            extend type Query {
              nodes: [ChildIface]
              result(locale: String!): String
                @resolver(
                  of: "nodes { edge { ... on ParentB { localized(locale: ${'$'}locale) } } }"
                  result: "ok"
                )
            }

            interface ChildIface { edge: ParentIface }

            type ChildA implements ChildIface {
              edge: ParentA @parent
            }

            type ChildB implements ChildIface {
              edge: ParentB
            }

            interface ParentIface { id: ID }

            type ParentA implements ParentIface {
              id: ID
              child: ChildA
            }

            type ParentB implements ParentIface {
              id: ID
              localized(locale: String!): String @resolver(result: "ok")
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `rejects variable below parent selected through abstract coordinate`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { user: UserImpl }
                    type UserImpl {
                      child: ChildImpl
                      localizedName(locale: String!): String
                      display(locale: String!): String
                    }
                    interface ChildIface { parent: UserImpl }
                    type ChildImpl implements ChildIface {
                      parent: UserImpl @parent
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    fun resolver(fragment: model.Fragment) = fieldResolverOf(fragment) { _, _ -> error("not invoked") }
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "user") to
                            resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                        schema.loweredSchema.requireObjectField("UserImpl", "child") to
                            resolver(schema.loweredSchema.emptyFragmentOf("UserImpl")),
                        schema.loweredSchema.requireObjectField("UserImpl", "localizedName") to
                            resolver(schema.loweredSchema.emptyFragmentOf("UserImpl")),
                        schema.loweredSchema.requireObjectField("UserImpl", "display") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on UserImpl {
                                      child {
                                        ... on ChildIface {
                                          parent { localizedName(locale: ${'$'}locale) }
                                        }
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                    )
                },
                variableProviders = { schema ->
                    val display = schema.loweredSchema.requireObjectField("UserImpl", "display")
                    mapOf(
                        Arguments.Variable.of(display, "locale") to
                            schema.loweredSchema.fromArgument(display, "locale"),
                    )
                },
            )
        }
    }

    @Test
    fun `rejects query fragment variable below parent selected through abstract coordinate`() {
        assertFailsWith<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query {
                      user: UserImpl
                      result(locale: String!): String
                    }
                    type UserImpl {
                      child: ChildImpl
                      localizedName(locale: String!): String
                    }
                    interface ChildIface { parent: UserImpl }
                    type ChildImpl implements ChildIface {
                      parent: UserImpl @parent
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    fun resolver(fragment: model.Fragment) = fieldResolverOf(fragment) { _, _ -> error("not invoked") }
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "user") to
                            resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                        schema.loweredSchema.requireObjectField("Query", "result") to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          user {
                                            child {
                                              ... on ChildIface {
                                                parent { localizedName(locale: ${'$'}locale) }
                                              }
                                            }
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                            ) { _, _, _ -> error("not invoked") },
                        schema.loweredSchema.requireObjectField("UserImpl", "child") to
                            resolver(schema.loweredSchema.emptyFragmentOf("UserImpl")),
                        schema.loweredSchema.requireObjectField("UserImpl", "localizedName") to
                            resolver(schema.loweredSchema.emptyFragmentOf("UserImpl")),
                    )
                },
                variableProviders = { schema ->
                    val result = schema.loweredSchema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "locale") to
                            schema.loweredSchema.fromArgument(result, "locale"),
                    )
                },
            )
        }
    }
}
