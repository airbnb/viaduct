package viaduct.tenant.runtime.execution.namedfragments

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

/**
 * Contract test for named fragment spreading in resolver selection sets.
 *
 * Verifies that a fragment defined via @GraphQLFragment and spread with `...FragmentName`
 * inside an objectValueFragment or queryValueFragment resolves correctly at execution time.
 */
@TestSchema(
    """
    extend type Query {
        "Return a User with id=<id>, name=\"User-<id>\""
        user(id: ID!): User @resolver
        "Return a User with id=\"viewer-42\", name=\"ViewerUser\""
        viewer: User @resolver
    }

    type User {
        id: ID!
        name: String!
        "Use objectValueFragment spread to read id and name; return \"<id>:<name>\""
        label: String! @resolver
        "Use objectValueFragment spread to read viewer name via queryValueFragment spread; return \"<viewer.name>-greeting\""
        greeting: String! @resolver
    }
"""
)
abstract class NamedFragmentsContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `named fragment spread in objectValueFragment resolves fields correctly`() {
        execute(
            """
            query {
                user(id: "alice") {
                    label
                }
            }
            """
        ).assertEquals {
            "data" to {
                "user" to {
                    "label" to "alice:User-alice"
                }
            }
        }
    }

    @Test
    fun `named fragment spread in queryValueFragment resolves fields correctly`() {
        execute(
            """
            query {
                user(id: "bob") {
                    greeting
                }
            }
            """
        ).assertEquals {
            "data" to {
                "user" to {
                    "greeting" to "ViewerUser-greeting"
                }
            }
        }
    }
}
