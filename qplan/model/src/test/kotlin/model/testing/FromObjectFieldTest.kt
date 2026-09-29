package model.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import model.Arguments
import model.EngineErrorData
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.requireField
import model.requireQueryTypeDef
import viaduct.graphql.schema.ViaductSchema

class FromObjectFieldTest {
    @Test
    fun `compiles aliases to canonical field keys`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Profile {
                  commonName(style: Int!): String!
                }

                type Query {
                  profile: Profile!
                }
                """.trimIndent(),
            ).schema

        val provider =
            schema.fromObjectField(
                objectFragmentSource =
                    """
                    fragment Provider on Query {
                      person: profile {
                        displayName: commonName(style: 2)
                      }
                    }
                    """.trimIndent(),
                responsePath = listOf("person", "displayName"),
            )

        assertEquals(
            listOf(
                ObjectEngineResult.Key.of(schema.requireField("Query", "profile"), emptyMap()),
                ObjectEngineResult.Key.of(
                    schema.requireField("Profile", "commonName"),
                    mapOf("style" to 2),
                ),
            ),
            provider.keyPath,
        )
    }

    @Test
    fun `aliases distinguish argument-distinct source occurrences`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  z(w: Int!): Int!
                }
                """.trimIndent(),
            ).schema
        val source =
            """
            fragment Provider on Query {
              z1: z(w: 1)
              z2: z(w: 2)
            }
            """.trimIndent()

        val provider =
            schema.fromObjectField(
                objectFragmentSource = source,
                responsePath = listOf("z2"),
            )

        assertEquals(
            listOf(ObjectEngineResult.Key.of(schema.requireField("Query", "z"), mapOf("w" to 2))),
            provider.keyPath,
        )
    }

    @Test
    fun `rejects narrowing branches even when every concrete type supplies the response key`() {
        val schema =
            TestWorld.fromSDL(
                """
                interface Foo {
                  id: ID!
                }

                type EU implements Foo {
                  id: ID!
                  commonName: String
                }

                type US implements Foo {
                  id: ID!
                  firstName: String
                }

                type Query {
                  foo: Foo
                }
                """.trimIndent(),
            ).schema

        val failure =
            assertFailsWith<IllegalArgumentException> {
                schema.fromObjectField(
                    objectFragmentSource =
                        """
                        fragment Provider on Foo {
                          ... on EU {
                            firstName: commonName
                          }
                          ... on US {
                            firstName
                          }
                        }
                        """.trimIndent(),
                    responsePath = listOf("firstName"),
                )
            }

        assertTrue(failure.message!!.contains("lossy type condition Foo to EU"))
    }

    @Test
    fun `allows a broadening type condition`() {
        val schema =
            TestWorld.fromSDL(
                """
                interface Named {
                  name: String!
                }

                type User implements Named {
                  name: String!
                }

                type Query {
                  user: User!
                }
                """.trimIndent(),
            ).schema

        val provider =
            schema.fromObjectField(
                objectFragmentSource =
                    """
                    fragment Provider on User {
                      ... on Named {
                        displayName: name
                      }
                    }
                    """.trimIndent(),
                responsePath = listOf("displayName"),
            )

        assertEquals(
            listOf(ObjectEngineResult.Key.of(schema.requireField("Named", "name"), emptyMap())),
            provider.keyPath,
        )
    }

    @Test
    fun `rejects list traversal and object terminals`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Profile {
                  name: String!
                }

                type Query {
                  profiles: [Profile!]!
                  profile: Profile!
                }
                """.trimIndent(),
            ).schema
        val source =
            """
            fragment Provider on Query {
              profiles {
                name
              }
              profile {
                name
              }
            }
            """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            schema.fromObjectField(source, listOf("profiles", "name"))
        }
        assertFailsWith<IllegalArgumentException> {
            schema.fromObjectField(source, listOf("profile"))
        }
    }

    @Test
    fun `rejects an incompatible source type`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                variableWorld(
                    schemaSDL =
                        """
                        type Query {
                          result: Int!
                          source: String!
                          consume(value: Int!): Int!
                        }
                        """.trimIndent(),
                    objectFragment =
                        """
                        fragment Provider on Query {
                          source
                          consume(value: ${'$'}value)
                        }
                        """.trimIndent(),
                    responsePath = listOf("source"),
                )
            }

        assertTrue(failure.message!!.contains("incompatible"))
    }

    @Test
    fun `nullable traversal requires a nullable or defaulted use location`() {
        val schemaSDL =
            """
            type Box {
              value: Int!
            }

            type Query {
              result: Int!
              box: Box
              consume(value: Int!): Int!
            }
            """.trimIndent()
        val fragment =
            """
            fragment Provider on Query {
              box {
                value
              }
              consume(value: ${'$'}value)
            }
            """.trimIndent()

        val failure =
            assertFailsWith<IllegalArgumentException> {
                variableWorld(schemaSDL, fragment, listOf("box", "value"))
            }

        assertTrue(failure.message!!.contains("incompatible"))

        variableWorld(
            schemaSDL = schemaSDL.replace("value: Int!):", "value: Int! = 1):"),
            objectFragment = fragment,
            responsePath = listOf("box", "value"),
        )
    }

    @Test
    fun `nullable traversal cannot supply a non-null list location`() {
        val schemaSDL =
            """
            type Box {
              values: [Int!]!
            }

            type Query {
              result: Int!
              box: Box
              consume(values: [Int]!): Int!
            }
            """.trimIndent()
        val fragment =
            """
            fragment Provider on Query {
              box {
                values
              }
              consume(values: ${'$'}value)
            }
            """.trimIndent()

        val failure =
            assertFailsWith<IllegalArgumentException> {
                variableWorld(schemaSDL, fragment, listOf("box", "values"))
            }

        assertTrue(failure.message!!.contains("incompatible"))
    }

    @Test
    fun `nullable traversal can supply a nullable list with non-null elements`() {
        val schemaSDL =
            """
            type Box {
              values: [Int!]!
            }

            type Query {
              result: Int!
              box: Box
              consume(values: [Int!]): Int!
            }
            """.trimIndent()
        val fragment =
            """
            fragment Provider on Query {
              box {
                values
              }
              consume(values: ${'$'}value)
            }
            """.trimIndent()

        variableWorld(schemaSDL, fragment, listOf("box", "values"))
    }

    @Test
    fun `accepts singleton coercion into nested input lists`() {
        variableWorld(
            schemaSDL =
                """
                type Query {
                  result: Int!
                  source: Int!
                  consume(value: [[Int!]!]!): Int!
                }
                """.trimIndent(),
            objectFragment =
                """
                fragment Provider on Query {
                  source
                  consume(value: ${'$'}value)
                }
                """.trimIndent(),
            responsePath = listOf("source"),
        )
    }

    private fun variableWorld(
        schemaSDL: String,
        objectFragment: String,
        responsePath: List<String>,
    ): TestWorld =
        TestWorld.fromSDL(
            schemaSDL = schemaSDL,
            fieldResolvers = { schema ->
                schema.requireQueryTypeDef().fields
                    .filter { field -> field.name != "V_A_typename" }
                    .associateWith {
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                            EngineErrorData.of()
                        }
                    } +
                    mapOf(
                        schema.requireField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(objectFragment)) { _, _ ->
                                1
                            },
                    )
            },
            variableProviders = { schema ->
                val owner = schema.requireField("Query", "result") as ViaductSchema.ObjectField
                mapOf(
                    Arguments.Variable.of(owner, "value") to
                        schema.fromObjectField(objectFragment, responsePath),
                )
            },
        )
}
