package viaduct.tenant.runtime.execution.inputtype

import java.math.BigDecimal
import java.math.BigInteger
import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

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
@TestSchema(
    """
    input UserInput {
      name: String!
      age: Int
      balance: BigDecimal
      serial: BigInteger
    }
    type User {
      name: String!
      age: Int
      balance: BigDecimal
      serial: BigInteger
    }
    extend type Query {
      "Receive input: UserInput! and limit: Int arguments; return a User with name=input.name, age=input.age"
      userByName(input: UserInput!, limit: Int): User @resolver
    }
"""
)
abstract class InputTypeContractTest : KotlinFeatureAppTestContractBase() {
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

    @Test
    fun `arbitrary precision scalar variables round trip`() {
        val balance = BigDecimal("12345678901234567890.12345678901234567890")
        val serial = BigInteger("123456789012345678901234567890")

        execute(
            query = """
                query(${'$'}input: UserInput!) {
                  userByName(input: ${'$'}input) {
                    balance
                    serial
                  }
                }
            """.trimIndent(),
            variables = mapOf(
                "input" to mapOf(
                    "name" to "Variable",
                    "balance" to balance,
                    "serial" to serial
                )
            )
        ).assertEquals {
            "data" to {
                "userByName" to {
                    "balance" to balance
                    "serial" to serial
                }
            }
        }
    }

    @Test
    fun `arbitrary precision scalar literals round trip`() {
        execute(
            query = """
                {
                  userByName(
                    input: {
                      name: "Literal"
                      balance: 98765432109876543210.98765432109876543210
                      serial: 987654321098765432109876543210
                    }
                  ) {
                    balance
                    serial
                  }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "userByName" to {
                    "balance" to BigDecimal("98765432109876543210.98765432109876543210")
                    "serial" to BigInteger("987654321098765432109876543210")
                }
            }
        }
    }
}
