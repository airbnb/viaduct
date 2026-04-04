package viaduct.tenant.runtime.fixtures.inputtypecontract

import org.junit.jupiter.api.Test
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Contract test for input type resolution patterns.
 *
 * Defines the SDL and assertions for:
 * - Input types with required and nullable fields
 * - Resolvers that receive and echo back input type arguments
 * - Inline input objects (not using variables)
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
abstract class InputTypeContractTest : FeatureAppTestBase() {
    init {
        sdl = """
            | #START_SCHEMA
            | input UserInput {
            |   name: String!
            |   age: Int
            | }
            | type User {
            |   name: String!
            |   age: Int
            | }
            | extend type Query {
            |   "Receive input: UserInput! and limit: Int arguments; return a User with name=input.name, age=input.age"
            |   userByName(input: UserInput!, limit: Int): User @resolver
            | }
            | #END_SCHEMA
        """.trimMargin()
    }

    @Test
    fun `resolverReceivesInputType`() {
        execute(
            query = "query(\$input: UserInput!) { userByName(input: \$input) { name age } }",
            variables = mapOf("input" to mapOf("name" to "Alice", "age" to 30))
        ).assertEquals {
            "data" to {
                "userByName" to {
                    "name" to "Alice"
                    "age" to 30
                }
            }
        }
    }

    @Test
    fun `inputTypeWithNullableField`() {
        execute(
            query = "query(\$input: UserInput!) { userByName(input: \$input) { name age } }",
            variables = mapOf("input" to mapOf("name" to "Bob"))
        ).assertEquals {
            "data" to {
                "userByName" to {
                    "name" to "Bob"
                    "age" to null
                }
            }
        }
    }

    @Test
    fun `inlineInputType`() {
        execute(
            query = "{ userByName(input: { name: \"Charlie\", age: 25 }) { name age } }"
        ).assertEquals {
            "data" to {
                "userByName" to {
                    "name" to "Charlie"
                    "age" to 25
                }
            }
        }
    }
}
