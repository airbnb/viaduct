package model.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import model.ArgumentResolutionError
import model.Arguments
import model.Fragment
import model.ObjectEngineResult
import model.Selection
import model.emptyFragmentOf
import model.fieldExpressions
import model.fragmentFrom
import model.requireArg
import model.requireField
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.selectionForestOf
import model.testing.TestWorld
import viaduct.graphql.schema.ViaductSchema

class ResolverDemandTest {
    @Test
    fun `includes field-relative variables in the resolver demand graph`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      x: Int
                      y(b: Int): Int
                      z(c: Int): Int
                      raw: Int
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireField("Query", "x") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      y(b: ${'$'}b)
                                      z(c: ${'$'}c)
                                      raw
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                        schema.loweredSchema.requireField("Query", "y") to resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                        schema.loweredSchema.requireField("Query", "z") to resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                        schema.loweredSchema.requireField("Query", "raw") to resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                    )
                },
                variableProviders = { schema ->
                    val owner = schema.loweredSchema.requireObjectField("Query", "x")
                    mapOf(
                        Arguments.Variable.of(owner, "b") to
                            schema.fromObjectField(
                                """
                                fragment ignored on Query {
                                  z(c: ${'$'}c)
                                }
                                """.trimIndent(),
                                listOf("z"),
                            ),
                        Arguments.Variable.of(owner, "c") to
                            schema.fromObjectField(
                                """
                                fragment ignored on Query {
                                  raw
                                }
                                """.trimIndent(),
                                listOf("raw"),
                            ),
                    )
                },
            )
        val schema = world.schemas
        val registry = world.resolverRegistry
        val x = schema.loweredSchema.requireObjectField("Query", "x")
        val y = schema.loweredSchema.requireObjectField("Query", "y")
        val z = schema.loweredSchema.requireObjectField("Query", "z")
        val raw = schema.loweredSchema.requireObjectField("Query", "raw")

        assertEquals(setOf(y, z, raw), registry.mayDemandFrom(x))
    }

    @Test
    fun `variable names are local to their defining field resolver`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      x: Int
                      y: Int
                      xSource: Int
                      ySource: Int
                      consume(value: Int): Int
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val empty = schema.loweredSchema.emptyFragmentOf("Query")
                    mapOf(
                        schema.loweredSchema.requireField("Query", "x") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      xSource
                                      consume(value: ${'$'}same)
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                        schema.loweredSchema.requireField("Query", "y") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      ySource
                                      consume(value: ${'$'}same)
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                        schema.loweredSchema.requireField("Query", "xSource") to resolver(empty),
                        schema.loweredSchema.requireField("Query", "ySource") to resolver(empty),
                        schema.loweredSchema.requireField("Query", "consume") to resolver(empty),
                    )
                },
                variableProviders = { schema ->
                    val x = schema.loweredSchema.requireObjectField("Query", "x")
                    val y = schema.loweredSchema.requireObjectField("Query", "y")
                    mapOf(
                        Arguments.Variable.of(x, "same") to
                            schema.fromObjectField(
                                "fragment ignored on Query { xSource }",
                                listOf("xSource"),
                            ),
                        Arguments.Variable.of(y, "same") to
                            schema.fromObjectField(
                                "fragment ignored on Query { ySource }",
                                listOf("ySource"),
                            ),
                    )
                },
            )
        val schema = world.schemas
        val x = schema.loweredSchema.requireObjectField("Query", "x")
        val y = schema.loweredSchema.requireObjectField("Query", "y")
        val xVariable = Arguments.Variable.of(x, "same")
        val yVariable = Arguments.Variable.of(y, "same")

        assertEquals(
            setOf(xVariable),
            world.resolverRegistry.resolver(x).variables.keys,
        )
        assertEquals(
            setOf(yVariable),
            world.resolverRegistry.resolver(y).variables.keys,
        )
        assertEquals(
            schema.loweredSchema.requireObjectField("Query", "xSource"),
            assertIs<VariableDefinition.FromField>(
                world.resolverRegistry.resolver(x).variables.getValue(xVariable),
            ).path.single().field,
        )
        assertEquals(
            schema.loweredSchema.requireObjectField("Query", "ySource"),
            assertIs<VariableDefinition.FromField>(
                world.resolverRegistry.resolver(y).variables.getValue(yVariable),
            ).path.single().field,
        )
    }

    @Test
    fun `defines a variable from an argument without adding provider demand`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      source(seed: Int!): Int
                      consume(value: Int): Int
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireField("Query", "source") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      consume(value: ${'$'}seed)
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                        schema.loweredSchema.requireField("Query", "consume") to
                            resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                    )
                },
                variableProviders = { schema ->
                    val source = schema.loweredSchema.requireObjectField("Query", "source")
                    mapOf(
                        Arguments.Variable.of(source, "seed") to
                            schema.loweredSchema.fromArgument(source, "seed"),
                    )
                },
            )
        val source = world.schema.requireObjectField("Query", "source")
        val consume = world.schema.requireObjectField("Query", "consume")
        val variable = Arguments.Variable.of(source, "seed")

        val definition =
            assertIs<VariableDefinition.FromArgument>(
                world.resolverRegistry.resolver(source).variables.getValue(variable),
            )
        assertEquals(source.requireArg("seed"), definition.argument)
        assertEquals(setOf(consume), world.resolverRegistry.mayDemandFrom(source))

        val resolver = world.resolverRegistry.resolver(source)
        val objectFragment = resolver.objectFragment.single()

        assertEquals(
            variable,
            objectFragment.key.arguments.fieldExpressions().getValue("value"),
        )
    }

    @Test
    fun `rejects an argument from a different resolver field`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Query {
                          source(seed: Int!): Int
                          other(seed: Int!): Int
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val empty = schema.loweredSchema.emptyFragmentOf("Query")
                        mapOf(
                            schema.loweredSchema.requireField("Query", "source") to resolver(empty),
                            schema.loweredSchema.requireField("Query", "other") to resolver(empty),
                        )
                    },
                    variableProviders = { schema ->
                        val source = schema.loweredSchema.requireObjectField("Query", "source")
                        val other = schema.loweredSchema.requireObjectField("Query", "other")
                        mapOf(
                            Arguments.Variable.of(source, "seed") to
                                schema.loweredSchema.fromArgument(other, "seed"),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("does not belong to Query/source"))
    }

    @Test
    fun `rejects variables beneath parent selections`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        directive @parent on FIELD_DEFINITION
                        type Query { company: Company }
                        type Company {
                          users: User
                          localizedName(locale: String!): String
                        }
                        type User {
                          parent: Company @parent
                          display(locale: String!): String
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.loweredSchema.requireField("Query", "company") to
                                resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                            schema.loweredSchema.requireField("Company", "users") to
                                resolver(schema.loweredSchema.emptyFragmentOf("Company")),
                            schema.loweredSchema.requireField("Company", "localizedName") to
                                resolver(schema.loweredSchema.emptyFragmentOf("Company")),
                            schema.loweredSchema.requireField("User", "display") to
                                resolver(
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on User {
                                          parent { localizedName(locale: ${'$'}locale) }
                                        }
                                        """.trimIndent(),
                                    ),
                                ),
                        )
                    },
                    variableProviders = { schema ->
                        val display = schema.loweredSchema.requireObjectField("User", "display")
                        mapOf(
                            Arguments.Variable.of(display, "locale") to
                                schema.loweredSchema.fromArgument(display, "locale"),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("must not use variables beneath @parent"))
    }

    @Test
    fun `rejects inclusion-condition variables beneath parent selections`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        directive @parent on FIELD_DEFINITION
                        type Query { root: Root }
                        type Root { child: Child }
                        type Child {
                          parent: Root @parent
                          dependency: Int
                          result(enabled: Boolean!): Int
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val result = schema.loweredSchema.requireObjectField("Child", "result")
                        mapOf(
                            result to
                                resolver(
                                    schema.fragmentFrom(
                                        """
                                        fragment ResultInput on Child {
                                          parent { child @include(if: ${'$'}enabled) { dependency } }
                                        }
                                        """.trimIndent(),
                                        variableField = result,
                                    ),
                                ),
                        )
                    },
                    variableProviders = { schema ->
                        val result = schema.loweredSchema.requireObjectField("Child", "result")
                        mapOf(
                            Arguments.Variable.of(result, "enabled") to
                                schema.loweredSchema.fromArgument(result, "enabled"),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("must not use variables beneath @parent"))
    }

    @Test
    fun `accepts a variable defined beneath parent and used outside parent`() {
        TestWorld.fromSDL(
            schemaSDL =
                """
                directive @parent on FIELD_DEFINITION
                type Query { root: Root }
                type Root { enabled: Boolean!, child: Child }
                type Child {
                  parent: Root @parent
                  dependency: Int
                  result: Int
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val result = schema.loweredSchema.requireObjectField("Child", "result")
                mapOf(
                    result to
                        resolver(
                            schema.fragmentFrom(
                                """
                                fragment ResultInput on Child {
                                  parent { enabled }
                                  dependency @include(if: ${'$'}enabled)
                                }
                                """.trimIndent(),
                                variableField = result,
                            ),
                        ),
                )
            },
            variableProviders = { schema ->
                val result = schema.loweredSchema.requireObjectField("Child", "result")
                mapOf(
                    Arguments.Variable.of(result, "enabled") to
                        schema.fromObjectField(
                            objectFragmentSource =
                                "fragment ResultInput on Child { parent { enabled } }",
                            responsePath = listOf("parent", "enabled"),
                            variableField = result,
                        ),
                )
            },
        )
    }

    @Test
    fun `rejects variable cycles`() = assertRejectedVariableCycle(mixedFragments = false)

    @Test
    fun `rejects mixed object and Query field variable cycles`() = assertRejectedVariableCycle(mixedFragments = true)

    private fun assertRejectedVariableCycle(mixedFragments: Boolean) {
        val objectFragment =
            "fragment ObjectInput on Query { " +
                "objectProvider: z(a: ${'$'}queryValue) " +
                (if (mixedFragments) "" else "queryProvider: z(a: ${'$'}objectValue)") +
                " }"
        val queryFragment =
            "fragment QueryInput on Query { queryProvider: z(a: ${'$'}objectValue) }"
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Query {
                          result: Int
                          z(a: Int): Int
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val empty = schema.loweredSchema.emptyFragmentOf("Query")
                        mapOf(
                            schema.loweredSchema.requireField("Query", "result") to
                                fieldResolverOf(
                                    objectFragment = schema.fragmentFrom(objectFragment),
                                    queryFragment =
                                        if (mixedFragments) {
                                            schema.fragmentFrom(queryFragment)
                                        } else {
                                            empty
                                        },
                                ) { _, _, _ -> 1 },
                            schema.loweredSchema.requireField("Query", "z") to resolver(empty),
                        )
                    },
                    variableProviders = { schema ->
                        val owner = schema.loweredSchema.requireObjectField("Query", "result")
                        mapOf(
                            Arguments.Variable.of(owner, "objectValue") to
                                schema.fromObjectField(
                                    objectFragment,
                                    listOf("objectProvider"),
                                ),
                            Arguments.Variable.of(owner, "queryValue") to
                                if (mixedFragments) {
                                    schema.fromQueryField(queryFragment, listOf("queryProvider"))
                                } else {
                                    schema.fromObjectField(objectFragment, listOf("queryProvider"))
                                },
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("demand cycle"), failure.message)
    }

    @Test
    fun `rejects provider paths outside their defining resolver fragment`() {
        val absent =
            assertFailsWith<IllegalArgumentException> {
                providerContainmentWorld(
                    ownerFragment =
                        """
                        fragment ignored on Query {
                          consume(value: ${'$'}value)
                        }
                        """.trimIndent(),
                    providerFragment = "fragment ignored on Query { source(id: 1) }",
                    providerResponsePath = listOf("source"),
                )
            }
        assertTrue(absent.message!!.contains("not contained"))

        val wrongRoot =
            assertFailsWith<IllegalArgumentException> {
                providerContainmentWorld(
                    ownerFragment =
                        """
                        fragment ignored on Query {
                          consume(value: ${'$'}value)
                          source(id: 1)
                        }
                        """.trimIndent(),
                    providerFragment = "fragment ignored on Payload { value }",
                    providerResponsePath = listOf("value"),
                )
            }
        assertTrue(wrongRoot.message!!.contains("not relative"))

        val argumentDistinct =
            assertFailsWith<IllegalArgumentException> {
                providerContainmentWorld(
                    ownerFragment =
                        """
                        fragment ignored on Query {
                          consume(value: ${'$'}value)
                          source(id: 1)
                        }
                        """.trimIndent(),
                    providerFragment = "fragment ignored on Query { source(id: 2) }",
                    providerResponsePath = listOf("source"),
                )
            }
        assertTrue(argumentDistinct.message!!.contains("not contained"))
    }

    @Test
    fun `rejects an incompatible fromObjectField use in a query fragment`() {
        val providerFragment = "fragment Provider on Query { provided: provider }"
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Query {
                          result: Int!
                          provider: String!
                          consume(value: Int!): Int!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val result = schema.loweredSchema.requireObjectField("Query", "result")
                        mapOf(
                            result to
                                fieldResolverOf(
                                    objectFragment = schema.fragmentFrom(providerFragment),
                                    queryFragment =
                                        schema.fragmentFrom(
                                            "fragment QueryUse on Query { consume(value: ${'$'}value) }",
                                        ),
                                ) { _, _, _ -> error("Not invoked") },
                            schema.loweredSchema.requireObjectField("Query", "provider") to
                                resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                            schema.loweredSchema.requireObjectField("Query", "consume") to
                                resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                        )
                    },
                    variableProviders = { schema ->
                        val result = schema.loweredSchema.requireObjectField("Query", "result")
                        mapOf(
                            Arguments.Variable.of(result, "value") to
                                schema.fromObjectField(providerFragment, listOf("provided")),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("incompatible with one of its argument locations"))
    }

    @Test
    fun `rejects an incompatible fromArgument use in a query fragment`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Query {
                          result(value: String!): Int!
                          consume(value: Int!): Int!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val result = schema.loweredSchema.requireObjectField("Query", "result")
                        mapOf(
                            result to
                                fieldResolverOf(
                                    objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                    queryFragment =
                                        schema.fragmentFrom(
                                            "fragment QueryUse on Query { consume(value: ${'$'}value) }",
                                        ),
                                ) { _, _, _ -> error("Not invoked") },
                            schema.loweredSchema.requireObjectField("Query", "consume") to
                                resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                        )
                    },
                    variableProviders = { schema ->
                        val result = schema.loweredSchema.requireObjectField("Query", "result")
                        mapOf(
                            Arguments.Variable.of(result, "value") to
                                schema.loweredSchema.fromArgument(result, "value"),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("incompatible with one of its argument locations"))
    }

    @Test
    fun `rejects an Int fromArgument used as an inclusion condition`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Query {
                          result(flag: Int!): Int!
                          dependency: Int!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val result = schema.loweredSchema.requireObjectField("Query", "result")
                        mapOf(
                            result to
                                resolver(
                                    schema.fragmentFrom(
                                        "fragment ResultInput on Query { " +
                                            "dependency @include(if: ${'$'}flag) }",
                                        variableField = result,
                                    ),
                                ),
                            schema.loweredSchema.requireObjectField("Query", "dependency") to
                                resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                        )
                    },
                    variableProviders = { schema ->
                        val result = schema.loweredSchema.requireObjectField("Query", "result")
                        mapOf(
                            Arguments.Variable.of(result, "flag") to
                                schema.loweredSchema.fromArgument(result, "flag"),
                        )
                    },
                )
            }

        assertTrue(
            failure.message!!.contains(
                "argument path flag is incompatible with an inclusion-condition location",
            ),
        )
    }

    @Test
    fun `rejects an Int fromObjectField used as a query inclusion condition`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Query {
                          result: Int!
                          enabled: Int!
                          dependency: Int!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val result = schema.loweredSchema.requireObjectField("Query", "result")
                        mapOf(
                            result to
                                fieldResolverOf(
                                    objectFragment =
                                        schema.fragmentFrom(
                                            "fragment ResultInput on Query { enabled }",
                                            variableField = result,
                                        ),
                                    queryFragment =
                                        schema.fragmentFrom(
                                            "fragment QueryInput on Query { " +
                                                "dependency @include(if: ${'$'}enabled) }",
                                            variableField = result,
                                        ),
                                ) { _, _, _ ->
                                    error("Not invoked")
                                },
                            schema.loweredSchema.requireObjectField("Query", "enabled") to
                                resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                            schema.loweredSchema.requireObjectField("Query", "dependency") to
                                resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                        )
                    },
                    variableProviders = { schema ->
                        val result = schema.loweredSchema.requireObjectField("Query", "result")
                        mapOf(
                            Arguments.Variable.of(result, "enabled") to
                                schema.fromObjectField(
                                    objectFragmentSource =
                                        "fragment ResultInput on Query { enabled }",
                                    responsePath = listOf("enabled"),
                                    variableField = result,
                                ),
                        )
                    },
                )
            }

        assertTrue(
            failure.message!!.contains(
                "object provider path enabled is incompatible with an " +
                    "inclusion-condition location",
            ),
        )
    }

    @Test
    fun `rejects a nullable Boolean used as an inclusion condition`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Query {
                          result(flag: Boolean): Int!
                          dependency: Int!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val result = schema.loweredSchema.requireObjectField("Query", "result")
                        mapOf(
                            result to
                                resolver(
                                    schema.fragmentFrom(
                                        "fragment ResultInput on Query { " +
                                            "dependency @include(if: ${'$'}flag) }",
                                        variableField = result,
                                    ),
                                ),
                            schema.loweredSchema.requireObjectField("Query", "dependency") to
                                resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                        )
                    },
                    variableProviders = { schema ->
                        val result = schema.loweredSchema.requireObjectField("Query", "result")
                        mapOf(
                            Arguments.Variable.of(result, "flag") to
                                schema.loweredSchema.fromArgument(result, "flag"),
                        )
                    },
                )
            }

        assertTrue(
            failure.message!!.contains(
                "argument path flag is incompatible with an inclusion-condition location",
            ),
        )
    }

    @Test
    fun `rejects a provider path behind a narrowing guard`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        interface Subject {
                          value: Int!
                        }

                        type First implements Subject {
                          value: Int!
                        }

                        type Second implements Subject {
                          value: Int!
                        }

                        type Query {
                          result: Int!
                          consume(value: Int!): Int!
                          subject: Subject!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.loweredSchema.requireField("Query", "result") to
                                resolver(
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          consume(value: ${'$'}value)
                                          subject {
                                            ... on First {
                                              value
                                            }
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                                ),
                            schema.loweredSchema.requireField("Query", "consume") to
                                resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                            schema.loweredSchema.requireField("Query", "subject") to
                                resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                        )
                    },
                    variableProviders = { schema ->
                        val owner = schema.loweredSchema.requireField("Query", "result") as ViaductSchema.ObjectField
                        mapOf(
                            Arguments.Variable.of(owner, "value") to
                                schema.fromObjectField(
                                    """
                                    fragment ignored on Query {
                                      subject {
                                        ... on Second {
                                          value
                                        }
                                      }
                                    }
                                    """.trimIndent(),
                                    listOf("subject", "value"),
                                ),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("lossy type condition Subject to Second"))
    }

    @Test
    fun `derives resolver demand from all reachable selections and their possible types`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = DEMAND_SCHEMA,
                nodeResolvers = { schema ->
                    val user = schema.loweredSchema.requireType("User") as ViaductSchema.Object
                    val admin = schema.loweredSchema.requireType("Admin") as ViaductSchema.Object
                    mapOf(
                        user to nodeResolverOf { _: String -> error("Not invoked") },
                        admin to nodeResolverOf { _: String -> error("Not invoked") },
                    )
                },
                fieldResolvers = { schema ->
                    val consumerFragment =
                        schema.fragmentFrom(
                            """
                            fragment ignored on Query {
                              node {
                                resolved {
                                  value
                                }
                              }
                            }
                            """.trimIndent(),
                        )
                    val outerFragment =
                        schema.fragmentFrom(
                            """
                            fragment ignored on Query {
                              consumer {
                                value
                              }
                            }
                            """.trimIndent(),
                        )
                    mapOf(
                        schema.loweredSchema.requireField("Query", "node") to
                            resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                        schema.loweredSchema.requireField("Query", "consumer") to
                            resolver(consumerFragment),
                        schema.loweredSchema.requireField("Query", "outer") to resolver(outerFragment),
                        schema.loweredSchema.requireField("User", "resolved") to
                            resolver(schema.loweredSchema.emptyFragmentOf("User")),
                        schema.loweredSchema.requireField("Admin", "resolved") to
                            resolver(schema.loweredSchema.emptyFragmentOf("Admin")),
                    )
                },
            )
        val schema = world.schemas
        val registry = world.resolverRegistry
        val queryNode = schema.loweredSchema.requireObjectField("Query", "node")
        val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
        val outer = schema.loweredSchema.requireObjectField("Query", "outer")
        val userResolved = schema.loweredSchema.requireObjectField("User", "resolved")
        val adminResolved = schema.loweredSchema.requireObjectField("Admin", "resolved")

        assertEquals(
            setOf(
                queryNode,
                userResolved,
                adminResolved,
            ),
            registry.mayDemandFrom(consumer),
        )
        assertEquals(setOf(consumer), registry.mayDemandFrom(outer))
        assertTrue(registry.mayDemandFrom(queryNode).isEmpty())
        assertTrue(registry.mayDemandFrom(userResolved).isEmpty())
        assertTrue(registry.mayDemandFrom(adminResolved).isEmpty())
    }

    @Test
    fun `rejects cyclic resolver demand`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = CYCLE_SCHEMA,
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.loweredSchema.requireField("Query", "a") to
                                resolver(
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          b {
                                            value
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                                ),
                            schema.loweredSchema.requireField("Query", "b") to
                                resolver(
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          a {
                                            value
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                                ),
                        )
                    },
                )
            }

        assertTrue(exception.message!!.contains("demand cycle"))
    }

    @Test
    fun `rejects direct Query-fragment resolver recursion`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = "type Query { value: Int! }",
                    fieldResolvers = { schema ->
                        val value = schema.loweredSchema.requireObjectField("Query", "value")
                        mapOf(
                            value to
                                fieldResolverOf(
                                    objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                    queryFragment =
                                        schema.fragmentFrom(
                                            "fragment ValueQuery on Query { value }",
                                        ),
                                ) { _, _, _ -> error("Not invoked") },
                        )
                    },
                )
            }

        assertTrue(exception.message!!.contains("demand cycle"))
    }

    @Test
    fun `rejects mutual Query-fragment resolver recursion`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = "type Query { first: Int!, second: Int! }",
                    fieldResolvers = { schema ->
                        val first = schema.loweredSchema.requireObjectField("Query", "first")
                        val second = schema.loweredSchema.requireObjectField("Query", "second")
                        val empty = schema.loweredSchema.emptyFragmentOf("Query")
                        mapOf(
                            first to
                                fieldResolverOf(
                                    objectFragment = empty,
                                    queryFragment =
                                        schema.fragmentFrom(
                                            "fragment FirstQuery on Query { second }",
                                        ),
                                ) { _, _, _ -> error("Not invoked") },
                            second to
                                fieldResolverOf(
                                    objectFragment = empty,
                                    queryFragment =
                                        schema.fragmentFrom(
                                            "fragment SecondQuery on Query { first }",
                                        ),
                                ) { _, _, _ -> error("Not invoked") },
                        )
                    },
                )
            }

        assertTrue(exception.message!!.contains("demand cycle"))
    }

    @Test
    fun `statically excluded demand does not form a resolver cycle`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      first: Int!
                      second: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireField("Query", "first") to
                            resolver(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { second @skip(if: true) }",
                                ),
                            ),
                        schema.loweredSchema.requireField("Query", "second") to
                            resolver(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { first }",
                                ),
                            ),
                    )
                },
            )
        val schema = world.schemas
        val first = schema.loweredSchema.requireObjectField("Query", "first")
        val second = schema.loweredSchema.requireObjectField("Query", "second")

        assertTrue(world.resolverRegistry.mayDemandFrom(first).isEmpty())
        assertEquals(setOf(first), world.resolverRegistry.mayDemandFrom(second))
    }

    @Test
    fun `conservatively rejects coordinate cycles broken by error arguments`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Query {
                          first(arg: Int!): Int!
                          second(arg: Int!): Int!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val parsedSecond =
                            schema
                                .fragmentFrom(
                                    "fragment ignored on Query { second(arg: 1) }",
                                ).subselections
                                .single()
                        val errorSecond =
                            Selection.of(
                                key =
                                    ObjectEngineResult.Key.of(
                                        parsedSecond.key.field,
                                        mapOf("arg" to ArgumentResolutionError),
                                    ),
                                possibleTypes = parsedSecond.possibleTypes,
                                subselections = parsedSecond.subselections,
                            )
                        mapOf(
                            schema.loweredSchema.requireField("Query", "first") to
                                resolver(
                                    Fragment.of(
                                        schema.loweredSchema.requireQueryTypeDef(),
                                        selectionForestOf(errorSecond),
                                    ),
                                ),
                            schema.loweredSchema.requireField("Query", "second") to
                                resolver(
                                    schema.fragmentFrom(
                                        "fragment ignored on Query { first(arg: 1) }",
                                    ),
                                ),
                        )
                    },
                )
            }

        assertTrue(exception.message!!.contains("demand cycle"))
    }

    private companion object {
        val DEMAND_SCHEMA =
            """
            interface Node {
              id: ID!
              resolved: Result
            }

            type User implements Node {
              id: ID!
              resolved: Result
            }

            type Admin implements Node {
              id: ID!
              resolved: Result
            }

            type Result {
              value: String
            }

            type Query {
              node: Node
              consumer: Result
              outer: Result
            }
            """.trimIndent()

        val CYCLE_SCHEMA =
            """
            type Result {
              value: String
            }

            type Query {
              a: Result
              b: Result
            }
            """.trimIndent()

        fun resolver(fragment: Fragment): FieldResolverDefinition =
            fieldResolverOf(
                objectFragment = fragment,
                function = { _, _ -> error("Not invoked") },
            )

        fun providerContainmentWorld(
            ownerFragment: String,
            providerFragment: String,
            providerResponsePath: List<String>,
        ): TestWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      value: Int!
                    }

                    type Query {
                      result: Int!
                      consume(value: Int!): Int!
                      source(id: Int!): Int!
                      payload: Payload!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireField("Query", "result") to
                            resolver(schema.fragmentFrom(ownerFragment)),
                        schema.loweredSchema.requireField("Query", "consume") to
                            resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                        schema.loweredSchema.requireField("Query", "source") to
                            resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                        schema.loweredSchema.requireField("Query", "payload") to
                            resolver(schema.loweredSchema.emptyFragmentOf("Query")),
                    )
                },
                variableProviders = { schema ->
                    val owner = schema.loweredSchema.requireField("Query", "result") as ViaductSchema.ObjectField
                    mapOf(
                        Arguments.Variable.of(owner, "value") to
                            schema.fromObjectField(
                                providerFragment,
                                providerResponsePath,
                            ),
                    )
                },
            )
    }
}
