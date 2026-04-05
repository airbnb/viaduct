package viaduct.tenant.runtime.execution.mapping

import org.junit.jupiter.api.Test
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.runtime.fixtures.TestSchema

/**
 * Contract test for GRTDomain/JsonDomain mapping utilities.
 *
 * Defines the SDL and assertions for:
 * - GRT → JSON conversion (synchronous output values)
 * - JSON → GRT conversion (synchronous input values)
 *
 * Note: This contract test is Kotlin-only because GRTDomain and JsonDomain
 * are not available in the Java Tenant API.
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
@TestSchema(
    """
    extend type Query {
      "example resolver that converts from synchronous GRT data"
      syncGrtToJson: String! @resolver

      "example resolver that parses synchronous input data to a GRT"
      inputJsonToGrt(json: String!): User! @resolver

      "Return a User with id=\"VXNlcjox\", name=\"Frodo Baggins\", dob=\"1954-09-22\""
      user: User! @resolver
    }

    type User implements Node @resolver {
        id: ID!
        name: String!
        "Derived field on User; return 1954"
        birthYear: Int! @resolver
        dob: Date!
    }
"""
)
abstract class MappingContractTest : FeatureAppTestBase() {
    @Test
    fun `syncGrtToJson -- converts synchronous output values`() {
        execute("{ syncGrtToJson }")
            .assertEquals {
                "data" to {
                    "syncGrtToJson" to """{"id":"VXNlcjox","name":"Frodo Baggins","dob":"1954-09-22","birthYear":1954,"__typename":"User"}"""
                }
            }
    }

    @Test
    fun `inputJsonToGrt -- converts synchronous input values`() {
        execute(
            """
                query (${'$'}json: String!) {
                  inputJsonToGrt(json: ${'$'}json) {
                    id
                    name
                    dob
                    birthYear
                  }
                }
            """.trimIndent(),
            mapOf("json" to """{"id":"VXNlcjox","name":"Frodo Baggins","dob":"1954-09-22","birthYear":1954}""")
        ).assertEquals {
            "data" to {
                "inputJsonToGrt" to {
                    "id" to "VXNlcjox"
                    "name" to "Frodo Baggins"
                    "dob" to "1954-09-22"
                    "birthYear" to 1954
                }
            }
        }
    }
}
