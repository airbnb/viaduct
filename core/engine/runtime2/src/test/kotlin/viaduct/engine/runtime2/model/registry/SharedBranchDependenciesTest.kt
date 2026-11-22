package viaduct.engine.runtime2.model.registry

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import viaduct.engine.runtime2.bootstrap.fromObjectField
import viaduct.engine.runtime2.bootstrap.fromQueryField
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.graphql.schema.ViaductSchema

class SharedBranchDependenciesTest {
    @Test
    fun `rejects a variable cycle within one shared branch`() {
        val fragment = "fragment Input on Query { shared { left: consume(arg: \$right) right: consume(arg: \$left) } }"
        val failure = assertThrows<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL = BRANCH_SCHEMA,
                fieldResolvers = { schema ->
                    branchResolvers(schema) +
                        (schema.loweredSchema.requireField("Query", "result") to resolver(schema.fragmentFrom(fragment)))
                },
                variableProviders = { schema ->
                    val owner = schema.loweredSchema.requireField("Query", "result") as ViaductSchema.ObjectField
                    listOf("left", "right").associate { name ->
                        Arguments.Variable.of(owner, name) to
                            schema.fromObjectField(fragment, listOf("shared", name), variableField = owner)
                    }
                },
            )
        }
        assertTrue(failure.message.orEmpty().contains("demand cycle"), failure.message)
        assertTrue(failure.message.orEmpty().contains("variable:left"), failure.message)
        assertTrue(failure.message.orEmpty().contains("variable:right"), failure.message)
    }

    @ParameterizedTest
    @EnumSource(ProviderFragment::class)
    fun `rejects a variable used in its own provider path arguments`(providerFragment: ProviderFragment) {
        val fragment = "fragment Input on Query { shared(id: \$value) { value } }"
        val failure = assertThrows<IllegalArgumentException> {
            TestWorld.fromSDL(
                schemaSDL = "type Branch { value: Int! } type Query { result: Int! shared(id: Int!): Branch! }",
                fieldResolvers = { schema ->
                    val owner = schema.loweredSchema.requireField("Query", "result") as ViaductSchema.ObjectField
                    val empty = schema.loweredSchema.emptyFragmentOf("Query")
                    val input = schema.fragmentFrom(fragment, variableField = owner)
                    mapOf(
                        owner to fieldResolverOf(
                            objectFragment = if (providerFragment == ProviderFragment.OBJECT) input else empty,
                            queryFragment = if (providerFragment == ProviderFragment.QUERY) input else empty,
                        ) { _, _, _ -> error("Not invoked") },
                        schema.loweredSchema.requireField("Query", "shared") to resolver(empty),
                    )
                },
                variableProviders = { schema ->
                    val owner = schema.loweredSchema.requireField("Query", "result") as ViaductSchema.ObjectField
                    mapOf(
                        Arguments.Variable.of(owner, "value") to when (providerFragment) {
                            ProviderFragment.OBJECT -> schema.fromObjectField(fragment, listOf("shared", "value"), variableField = owner)
                            ProviderFragment.QUERY -> schema.fromQueryField(fragment, listOf("shared", "value"), variableField = owner)
                        },
                    )
                },
            )
        }
        assertTrue(failure.message.orEmpty().contains("demand cycle"), failure.message)
        assertTrue(failure.message.orEmpty().contains("variable:value"), failure.message)
    }

    @Test
    fun `accepts statically excluded variable uses within a shared branch`() {
        for (directive in listOf("@skip(if: true)", "@include(if: false)")) {
            for (consumer in listOf("a { consume(arg: ${'$'}value) $directive }", "a $directive { consume(arg: ${'$'}value) }")) {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result: Int! @resolver(
                        of: "provider: a { value } consumer: $consumer"
                        variables: [{name: "value", fromObjectField: ["provider", "value"]}]
                        script: "throw 'Not invoked'"
                      )
                      a: Branch! @resolver(script: "throw 'Not invoked'")
                      b: Branch! @resolver(script: "throw 'Not invoked'")
                    }
                    type Branch {
                      value: Int!
                      consume(arg: Int!): Int! @resolver(script: "throw 'Not invoked'")
                    }
                    """.trimIndent(),
                )
            }
        }
    }

    @Test
    fun `accepts a provider and use in the same structural branch`() {
        TestWorld.fromDSL(
            """
            extend type Query {
              result: Int! @resolver(
                of: "shared { value consume(arg: ${'$'}value) }"
                variables: [{name: "value", fromObjectField: ["shared", "value"]}]
                script: "throw 'Not invoked'"
              )
              shared: Branch! @resolver(script: "throw 'Not invoked'")
            }
            type Branch {
              value: Int!
              consume(arg: Int!): Int! @resolver(script: "throw 'Not invoked'")
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `accepts a shared passive provider and use prefix`() {
        TestWorld.fromDSL(
            """
            extend type Query { parent: Parent! @resolver(script: "throw 'Not invoked'") }
            type Parent {
              result: Int! @resolver(
                of: "shared { value consume(arg: ${'$'}value) }"
                variables: [{name: "value", fromObjectField: ["shared", "value"]}]
                script: "throw 'Not invoked'"
              )
              shared: Branch!
            }
            type Branch {
              value: Int!
              consume(arg: Int!): Int! @resolver(script: "throw 'Not invoked'")
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `accepts overlap introduced by a transitive resolver prerequisite`() {
        TestWorld.fromDSL(
            """
            extend type Query {
              result: Int! @resolver(
                of: "child { field(arg: ${'$'}value) } common"
                variables: [{name: "value", fromObjectField: ["common"]}]
                script: "throw 'Not invoked'"
              )
              common: Int! @resolver(of: "child { field(arg: 1) }", script: "throw 'Not invoked'")
              child: Child! @resolver(script: "throw 'Not invoked'")
            }
            type Child { field(arg: Int!): Int! @resolver(script: "throw 'Not invoked'") }
            """.trimIndent(),
        )
    }

    @Test
    fun `accepts independent field providers across shared branches`() {
        TestWorld.fromDSL(
            """
            extend type Query {
              result: Int! @resolver(
                of: "a { value consume(arg: ${'$'}v) } b { value consume(arg: ${'$'}w) }"
                variables: [{name: "v", fromObjectField: ["b", "value"]}, {name: "w", fromObjectField: ["a", "value"]}]
                script: "throw 'Not invoked'"
              )
              a: Branch! @resolver(script: "throw 'Not invoked'")
              b: Branch! @resolver(script: "throw 'Not invoked'")
            }
            type Branch {
              value: Int!
              consume(arg: Int!): Int! @resolver(script: "throw 'Not invoked'")
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `accepts linear and independent variable branch orders`() {
        TestWorld.fromDSL(
            """
            extend type Query {
              result: Int! @resolver(
                of: "a { value } b { value consume(arg: ${'$'}w) } c { consume(arg: ${'$'}v) } d { consume(arg: ${'$'}independent) }"
                variables: [
                  {name: "w", fromObjectField: ["a", "value"]}
                  {name: "v", fromObjectField: ["b", "value"]}
                  {name: "independent", fromObjectField: ["a", "value"]}
                ]
                script: "throw 'Not invoked'"
              )
              a: Branch! @resolver(script: "throw 'Not invoked'")
              b: Branch! @resolver(script: "throw 'Not invoked'")
              c: Branch! @resolver(script: "throw 'Not invoked'")
              d: Branch! @resolver(script: "throw 'Not invoked'")
            }
            type Branch {
              value: Int!
              consume(arg: Int!): Int! @resolver(script: "throw 'Not invoked'")
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `accepts argument-distinct occurrences within one structural branch`() {
        TestWorld.fromDSL(
            """
            extend type Query {
              result: Int! @resolver(
                of: "providerChild: child(id: 1) { value } consumerChild: child(id: 2) { consume(arg: ${'$'}value) }"
                variables: [{name: "value", fromObjectField: ["providerChild", "value"]}]
                script: "throw 'Not invoked'"
              )
              child(id: Int!): Branch! @resolver(script: "throw 'Not invoked'")
            }
            type Branch {
              value: Int!
              consume(arg: Int!): Int! @resolver(script: "throw 'Not invoked'")
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `finds variables nested in input objects and lists`() {
        TestWorld.fromDSL(
            """
            extend type Query {
              result: Int! @resolver(
                of: "source consume(config: {values: [1, ${'$'}value]})"
                variables: [{name: "value", fromObjectField: ["source"]}]
                script: "throw 'Not invoked'"
              )
              source: Int! @resolver(script: "throw 'Not invoked'")
              consume(config: Config!): Int! @resolver(script: "throw 'Not invoked'")
            }
            input Config { values: [Int!]! }
            """.trimIndent(),
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
                val user = schema.loweredSchema.requireType("User") as ViaductSchema.Object
                mapOf(user to nodeResolverOf { _: String -> error("Not invoked") })
            },
            fieldResolvers = { schema ->
                mapOf(
                    schema.loweredSchema.requireField("Query", "result") to
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
                    schema.loweredSchema.requireField("Query", "user") to
                        resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                    schema.loweredSchema.requireField("Query", "consume") to
                        resolver(schema.loweredSchema.emptyFragmentOf("Query")),
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

        fun branchResolvers(schema: ViaductAndGJSchema): Map<ViaductSchema.Field, FieldResolverDefinition> =
            mapOf(
                schema.loweredSchema.requireField("Query", "result") to
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
                schema.loweredSchema.requireField("Query", "shared") to resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                schema.loweredSchema.requireField("Branch", "consume") to
                    resolver(schema.loweredSchema.emptyFragmentOf("Branch")),
            )

        fun variable(
            schema: ViaductAndGJSchema,
            name: String,
            provider: String,
            responsePath: List<String>,
        ): Map<Arguments.Variable, FromField> {
            val owner = schema.loweredSchema.requireField("Query", "result") as ViaductSchema.ObjectField
            return mapOf(
                Arguments.Variable.of(owner, name) to
                    schema.fromObjectField(provider, responsePath),
            )
        }

        fun resolver(fragment: Fragment): FieldResolverDefinition = fieldResolverOf(fragment) { _, _ -> error("Not invoked") }
    }
}
