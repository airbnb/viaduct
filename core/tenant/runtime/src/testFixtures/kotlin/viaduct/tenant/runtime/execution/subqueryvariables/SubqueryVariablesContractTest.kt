package viaduct.tenant.runtime.execution.subqueryvariables

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

@TestSchema(
    """
    extend type Query {
      container: Container @resolver
      echoInput(input: SubqueryInput!): String! @resolver
    }

    type Container {
      queryWithInputVariable(input: SubqueryInput!): String! @resolver
    }

    input SubqueryInput {
      count: Int!
      statuses: [SubqueryStatus!]!
    }

    enum SubqueryStatus {
      ACCEPT
      DECLINE
    }
"""
)
abstract class SubqueryVariablesContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `ctx query accepts generated input values as variables`() {
        execute(
            query = """
                query {
                    container {
                        queryWithInputVariable(input: { count: 1, statuses: [ACCEPT] })
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "container" to {
                    "queryWithInputVariable" to "1:ACCEPT"
                }
            }
        }
    }
}
