package viaduct.tenant.runtime.execution.queryselections

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

/**
 * Contract test for the Query Selections feature.
 *
 * Defines the SDL and assertions for:
 * - Resolvers that access root Query data via queryValueFragment
 * - Null safety when a queryValueFragment field returns null
 * - Recursive resolver dependencies through Query Selections
 * - Mutation fields that load Query selections with variables
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
@TestSchema(
    """
    extend type Query {
        "Return a User with id=\"viewer-123\", name=\"ViewerUser\""
        viewer: User @resolver
        "Return null"
        viewerOrNull: User @resolver
        "Return a User with id=<id>, name=\"User-<id>\""
        user(id: ID!): User @resolver
    }

    extend type Mutation {
        "Use queryValueFragment to fetch viewer and user(id: userId); return UpdateResult(success=true, message=\"Updated user User-<userId> (<userId>) with info from viewer ViewerUser (viewer-123)\")"
        updateUserWithViewerInfo(userId: ID!): UpdateResult! @resolver
    }

    type User {
        id: ID!
        name: String!
        "Use queryValueFragment to fetch viewer; return \"<user.id>-displayedBy-<viewer.name>\""
        displayName: String! @resolver
        "Use queryValueFragment to fetch viewerOrNull; when null return \"<user.id>-displayedBy-Unknown\""
        displayNameFromNullViewer: String! @resolver
        "Use queryValueFragment to fetch viewer with displayName; return \"Hello User-<id>, from <viewer.id> (displayed by <viewer.displayName>)\""
        greeting: String! @resolver
    }

    type UpdateResult {
        success: Boolean!
        message: String!
    }
"""
)
abstract class QuerySelectionsContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `core functionality - fetches and combines object and query data`() {
        execute(
            """
            query {
                user(id: "test-user") {
                    displayName
                }
            }
        """
        ).assertEquals {
            "data" to {
                "user" to {
                    "displayName" to "test-user-displayedBy-ViewerUser"
                }
            }
        }
    }

    @Test
    fun `recursive dependency - resolver uses field that also uses query selections`() {
        execute(
            """
            query {
                user(id: "complex-user") {
                    greeting
                }
            }
        """
        ).assertEquals {
            "data" to {
                "user" to {
                    "greeting" to "Hello User-complex-user, from viewer-123 (displayed by viewer-123-displayedBy-ViewerUser)"
                }
            }
        }
    }

    @Test
    fun `null safety - handles null data from queryValueFragment gracefully`() {
        execute(
            """
            query {
                user(id: "null-test") {
                    displayNameFromNullViewer
                }
            }
        """
        ).assertEquals {
            "data" to {
                "user" to {
                    "displayNameFromNullViewer" to "null-test-displayedBy-Unknown"
                }
            }
        }
    }

    @Test
    fun `mutation field loads selections on Query - combines viewer and user data`() {
        execute(
            """
            mutation {
                updateUserWithViewerInfo(userId: "mutation-user") {
                    success
                    message
                }
            }
        """
        ).assertEquals {
            "data" to {
                "updateUserWithViewerInfo" to {
                    "success" to true
                    "message" to "Updated user User-mutation-user (mutation-user) with info from viewer ViewerUser (viewer-123)"
                }
            }
        }
    }
}
