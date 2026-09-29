package model.registry

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import model.Arguments
import model.Fragment
import model.emptyFragmentOf
import model.fragmentFrom
import model.requireField
import model.requireType
import model.testing.FieldResolverDefinition
import model.testing.FromField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import model.testing.nodeResolverOf
import viaduct.graphql.schema.ViaductSchema

class BranchOrderInvariantTest {
    @Test
    fun `rejects a provider and use in the same structural branch`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = BRANCH_SCHEMA,
                    fieldResolvers = ::branchResolvers,
                    variableProviders = { schema ->
                        variable(
                            schema = schema,
                            name = "value",
                            provider = "fragment ignored on Query { shared { value } }",
                            responsePath = listOf("shared", "value"),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("Query contains a cycle shared -> shared"))
        assertTrue(failure.message!!.contains("provider path shared/value"))
        assertTrue(failure.message!!.contains("use path shared/consume"))
    }

    @Test
    fun `rejects a shared passive provider and use prefix`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Branch {
                          value: Int!
                          consume(arg: Int!): Int!
                        }

                        type Parent {
                          result: Int!
                          shared: Branch!
                        }

                        type Query {
                          parent: Parent!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.requireField("Query", "parent") to
                                resolver(schema.emptyFragmentOf("Query")),
                            schema.requireField("Parent", "result") to
                                resolver(
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Parent {
                                          shared {
                                            value
                                            consume(arg: ${'$'}value)
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                                ),
                            schema.requireField("Branch", "consume") to
                                resolver(schema.emptyFragmentOf("Branch")),
                        )
                    },
                    variableProviders = { schema ->
                        val owner = schema.requireField("Parent", "result") as ViaductSchema.ObjectField
                        mapOf(
                            Arguments.Variable.of(owner, "value") to
                                schema.fromObjectField(
                                    "fragment ignored on Parent { shared { value } }",
                                    listOf("shared", "value"),
                                ),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("Parent contains a cycle shared -> shared"))
    }

    @Test
    fun `rejects overlap introduced by a transitive resolver prerequisite`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Child {
                          field(arg: Int!): Int!
                        }

                        type Query {
                          result: Int!
                          common: Int!
                          child: Child!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.requireField("Query", "result") to
                                resolver(
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          child {
                                            field(arg: ${'$'}value)
                                          }
                                          common
                                        }
                                        """.trimIndent(),
                                    ),
                                ),
                            schema.requireField("Query", "common") to
                                resolver(
                                    schema.fragmentFrom(
                                        "fragment ignored on Query { child { field(arg: 1) } }",
                                    ),
                                ),
                            schema.requireField("Query", "child") to
                                resolver(schema.emptyFragmentOf("Query")),
                            schema.requireField("Child", "field") to
                                resolver(schema.emptyFragmentOf("Child")),
                        )
                    },
                    variableProviders = { schema ->
                        variable(
                            schema = schema,
                            name = "value",
                            provider = "fragment ignored on Query { common }",
                            responsePath = listOf("common"),
                        )
                    },
                )
            }

        assertTrue(
            failure.message!!.contains("Query contains a cycle child -> child"),
            failure.message,
        )
        assertTrue(failure.message!!.contains("production path child -> common"))
        assertTrue(failure.message!!.contains("variable \$value"))
    }

    @Test
    fun `rejects a cross-variable branch-order cycle`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = TWO_BRANCH_SCHEMA,
                    fieldResolvers = { schema ->
                        twoBranchResolvers(
                            schema,
                            """
                            fragment ignored on Query {
                              a {
                                value
                                consume(arg: ${'$'}v)
                              }
                              b {
                                value
                                consume(arg: ${'$'}w)
                              }
                            }
                            """.trimIndent(),
                        )
                    },
                    variableProviders = { schema ->
                        variable(
                            schema,
                            name = "v",
                            provider = "fragment ignored on Query { b { value } }",
                            responsePath = listOf("b", "value"),
                        ) +
                            variable(
                                schema,
                                name = "w",
                                provider = "fragment ignored on Query { a { value } }",
                                responsePath = listOf("a", "value"),
                            )
                    },
                )
            }

        assertTrue(
            failure.message!!.contains("Query contains a cycle a -> b -> a"),
            failure.message,
        )
        assertTrue(failure.message!!.contains("variable \$v"))
        assertTrue(failure.message!!.contains("variable \$w"))
    }

    @Test
    fun `accepts linear and independent variable branch orders`() {
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Branch {
                  value: Int!
                  consume(arg: Int!): Int!
                }

                type Query {
                  result: Int!
                  a: Branch!
                  b: Branch!
                  c: Branch!
                  d: Branch!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val emptyQuery = schema.emptyFragmentOf("Query")
                mapOf(
                    schema.requireField("Query", "result") to
                        resolver(
                            schema.fragmentFrom(
                                """
                                fragment ignored on Query {
                                  a { value }
                                  b {
                                    value
                                    consume(arg: ${'$'}w)
                                  }
                                  c { consume(arg: ${'$'}v) }
                                  d { consume(arg: ${'$'}independent) }
                                }
                                """.trimIndent(),
                            ),
                        ),
                    schema.requireField("Query", "a") to resolver(emptyQuery),
                    schema.requireField("Query", "b") to resolver(emptyQuery),
                    schema.requireField("Query", "c") to resolver(emptyQuery),
                    schema.requireField("Query", "d") to resolver(emptyQuery),
                    schema.requireField("Branch", "consume") to
                        resolver(schema.emptyFragmentOf("Branch")),
                )
            },
            variableProviders = { schema ->
                variable(
                    schema,
                    name = "w",
                    provider = "fragment ignored on Query { a { value } }",
                    responsePath = listOf("a", "value"),
                ) +
                    variable(
                        schema,
                        name = "v",
                        provider = "fragment ignored on Query { b { value } }",
                        responsePath = listOf("b", "value"),
                    ) +
                    variable(
                        schema,
                        name = "independent",
                        provider = "fragment ignored on Query { a { value } }",
                        responsePath = listOf("a", "value"),
                    )
            },
        )
    }

    @Test
    fun `collapses argument-distinct occurrences into one structural branch`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Branch {
                          value: Int!
                          consume(arg: Int!): Int!
                        }

                        type Query {
                          result: Int!
                          child(id: Int!): Branch!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.requireField("Query", "result") to
                                resolver(
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          providerChild: child(id: 1) { value }
                                          consumerChild: child(id: 2) { consume(arg: ${'$'}value) }
                                        }
                                        """.trimIndent(),
                                    ),
                                ),
                            schema.requireField("Query", "child") to
                                resolver(schema.emptyFragmentOf("Query")),
                            schema.requireField("Branch", "consume") to
                                resolver(schema.emptyFragmentOf("Branch")),
                        )
                    },
                    variableProviders = { schema ->
                        variable(
                            schema = schema,
                            name = "value",
                            provider =
                                "fragment ignored on Query { providerChild: child(id: 1) { value } }",
                            responsePath = listOf("providerChild", "value"),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("Query contains a cycle child -> child"))
    }

    @Test
    fun `finds variables nested in input objects and lists`() {
        TestWorld.fromSDL(
            schemaSDL =
                """
                input Config {
                  values: [Int!]!
                }

                type Query {
                  result: Int!
                  source: Int!
                  consume(config: Config!): Int!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                mapOf(
                    schema.requireField("Query", "result") to
                        resolver(
                            schema.fragmentFrom(
                                """
                                fragment ignored on Query {
                                  source
                                  consume(config: { values: [1, ${'$'}value] })
                                }
                                """.trimIndent(),
                            ),
                        ),
                    schema.requireField("Query", "source") to
                        resolver(schema.emptyFragmentOf("Query")),
                    schema.requireField("Query", "consume") to
                        resolver(schema.emptyFragmentOf("Query")),
                )
            },
            variableProviders = { schema ->
                variable(
                    schema = schema,
                    name = "value",
                    provider = "fragment ignored on Query { source }",
                    responsePath = listOf("source"),
                )
            },
        )
    }

    @Test
    fun `includes node reference prerequisites in provider production`() {
        TestWorld.fromSDL(
            schemaSDL =
                """
                interface Node {
                  id: ID!
                }

                type User implements Node {
                  id: ID!
                }

                type Query {
                  result: Int!
                  user: User!
                  consume(id: ID!): Int!
                }
                """.trimIndent(),
            nodeResolvers = { schema ->
                val user = schema.requireType("User") as ViaductSchema.Object
                mapOf(user to nodeResolverOf { _: String -> error("Not invoked") })
            },
            fieldResolvers = { schema ->
                mapOf(
                    schema.requireField("Query", "result") to
                        resolver(
                            schema.fragmentFrom(
                                """
                                fragment ignored on Query {
                                  user { id }
                                  consume(id: ${'$'}id)
                                }
                                """.trimIndent(),
                            ),
                        ),
                    schema.requireField("Query", "user") to
                        resolver(schema.emptyFragmentOf("Query")),
                    schema.requireField("Query", "consume") to
                        resolver(schema.emptyFragmentOf("Query")),
                )
            },
            variableProviders = { schema ->
                variable(
                    schema = schema,
                    name = "id",
                    provider = "fragment ignored on Query { user { id } }",
                    responsePath = listOf("user", "id"),
                )
            },
        )
    }

    private companion object {
        val BRANCH_SCHEMA =
            """
            type Branch {
              value: Int!
              consume(arg: Int!): Int!
            }

            type Query {
              result: Int!
              shared: Branch!
            }
            """.trimIndent()

        val TWO_BRANCH_SCHEMA =
            """
            type Branch {
              value: Int!
              consume(arg: Int!): Int!
            }

            type Query {
              result: Int!
              a: Branch!
              b: Branch!
            }
            """.trimIndent()

        fun branchResolvers(schema: ViaductSchema): Map<ViaductSchema.Field, FieldResolverDefinition> =
            mapOf(
                schema.requireField("Query", "result") to
                    resolver(
                        schema.fragmentFrom(
                            """
                            fragment ignored on Query {
                              shared {
                                value
                                consume(arg: ${'$'}value)
                              }
                            }
                            """.trimIndent(),
                        ),
                    ),
                schema.requireField("Query", "shared") to resolver(schema.emptyFragmentOf("Query")),
                schema.requireField("Branch", "consume") to
                    resolver(schema.emptyFragmentOf("Branch")),
            )

        fun twoBranchResolvers(
            schema: ViaductSchema,
            resultFragment: String,
        ): Map<ViaductSchema.Field, FieldResolverDefinition> =
            mapOf(
                schema.requireField("Query", "result") to
                    resolver(schema.fragmentFrom(resultFragment)),
                schema.requireField("Query", "a") to resolver(schema.emptyFragmentOf("Query")),
                schema.requireField("Query", "b") to resolver(schema.emptyFragmentOf("Query")),
                schema.requireField("Branch", "consume") to
                    resolver(schema.emptyFragmentOf("Branch")),
            )

        fun variable(
            schema: ViaductSchema,
            name: String,
            provider: String,
            responsePath: List<String>,
        ): Map<Arguments.Variable, FromField> {
            val owner = schema.requireField("Query", "result") as ViaductSchema.ObjectField
            return mapOf(
                Arguments.Variable.of(owner, name) to
                    schema.fromObjectField(provider, responsePath),
            )
        }

        fun resolver(fragment: Fragment): FieldResolverDefinition = fieldResolverOf(fragment) { _, _ -> error("Not invoked") }
    }
}
