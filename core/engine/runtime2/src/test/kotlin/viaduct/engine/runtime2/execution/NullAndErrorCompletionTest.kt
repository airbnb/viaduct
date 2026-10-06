package viaduct.engine.runtime2.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import viaduct.engine.runtime2.execution.testing.ExecutionTestFixtureResource

class NullAndErrorCompletionTest : ExecutionTestFixtureResource {
    @Test
    fun `completes nullable fields and list elements without errors`() {
        val fixture =
            fixtureFromResolverDSL(
                schemaSDL = SCHEMA,
                resolverSchemaSDL = RESOLVERS,
            )

        val result =
            fixture.runQuery(
                """
                {
                  healthy
                  nullValue
                  user {
                    requiredValue
                    optionalNote
                  }
                  items
                }
                """.trimIndent(),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf(
                "healthy" to 1,
                "nullValue" to null,
                "user" to
                    mapOf(
                        "requiredValue" to 7,
                        "optionalNote" to null,
                    ),
                "items" to listOf(1, null, 3),
            ),
            result.getData(),
        )
    }

    @Test
    fun `reports errors and applies GraphQL null bubbling`() {
        val fixture =
            fixtureFromResolverDSL(
                schemaSDL = SCHEMA,
                resolverSchemaSDL = RESOLVERS,
            )

        val result =
            fixture.runQuery(
                """
                {
                  healthy
                  failedValue
                  failedUser {
                    requiredValue
                  }
                  failedItems
                  strictItems
                }
                """.trimIndent(),
            )

        assertEquals(
            mapOf(
                "healthy" to 1,
                "failedValue" to null,
                "failedUser" to null,
                "failedItems" to listOf(1, null, 3),
                "strictItems" to null,
            ),
            result.getData(),
        )
        assertEquals(4, result.errors.size)
        assertEquals(
            setOf<List<Any>>(
                listOf("failedValue"),
                listOf("failedUser", "requiredValue"),
                listOf("failedItems", 1),
                listOf("strictItems", 1),
            ),
            result.errors.mapTo(linkedSetOf()) { error -> assertNotNull(error.path) },
        )
        assertTrue(
            result.errors.all { error ->
                error.message.contains("QPlan field resolution failed")
            },
        )
    }

    private companion object {
        val SCHEMA =
            """
            type Query {
              healthy: Int!
              nullValue: Int
              user: User!
              items: [Int]
              failedValue: Int
              failedUser: User
              failedItems: [Int]
              strictItems: [Int!]
            }

            type User {
              requiredValue: Int!
              optionalNote: Int
            }
            """.trimIndent()

        val RESOLVERS =
            """
            extend type Query {
              healthy: Int! @resolver(result: 1)
              nullValue: Int @resolver(result: null)
              user: User!
                @resolver(result: {requiredValue: 7, optionalNote: null})
              items: [Int] @resolver(result: [1, null, 3])
              failedValue: Int @resolver(result: "ERROR")
              failedUser: User
                @resolver(result: {requiredValue: "ERROR"})
              failedItems: [Int] @resolver(result: [1, "ERROR", 3])
              strictItems: [Int!] @resolver(result: [1, "ERROR", 3])
            }

            type User {
              requiredValue: Int!
              optionalNote: Int
            }
            """.trimIndent()
    }
}
