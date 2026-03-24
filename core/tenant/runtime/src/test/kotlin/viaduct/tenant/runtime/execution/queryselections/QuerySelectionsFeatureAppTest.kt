@file:Suppress("unused", "ClassName", "PackageDirectoryMismatch")

package viaduct.tenant.runtime.execution.queryselections

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.Variable
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.execution.queryselections.resolverbases.MutationResolvers
import viaduct.tenant.runtime.execution.queryselections.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.queryselections.resolverbases.UserResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Integration test for the Query Selections feature that allows resolvers to access
 * both parent object data and root query data through queryValueFragment parameter.
 *
 * This test focuses on the unique runtime integration aspects that unit tests cannot cover:
 * 1. Actual execution of queryValueFragment at runtime
 * 2. Population of ctx.queryValue in resolver context
 * 3. Complex resolver interdependencies using Query Selections
 */
class QuerySelectionsFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        #START_SCHEMA
        extend type Query {
            viewer: User @resolver
            viewerOrNull: User @resolver
            user(id: ID!): User @resolver
        }

        extend type Mutation {
            updateUserWithViewerInfo(userId: ID!): UpdateResult! @resolver
        }

        type User {
            id: ID!
            name: String!
            displayName: String! @resolver
            displayNameFromNullViewer: String! @resolver
            greeting: String! @resolver
        }

        type UpdateResult {
            success: Boolean!
            message: String!
        }
        #END_SCHEMA
    """

    // Standard resolver for viewer - provides root query data
    @Resolver
    class Query_ViewerResolver : QueryResolvers.Viewer() {
        override suspend fun resolve(ctx: Context): User {
            return User.Builder(ctx)
                .id("viewer-123")
                .name("ViewerUser")
                .build()
        }
    }

    // Resolver for viewerOrNull that returns null to test error handling
    @Resolver
    class Query_ViewerOrNullResolver : QueryResolvers.ViewerOrNull() {
        override suspend fun resolve(ctx: Context): User? {
            return null
        }
    }

    // Standard resolver for user lookup - provides object data
    @Resolver
    class Query_UserResolver : QueryResolvers.User() {
        override suspend fun resolve(ctx: Context): User {
            val userId = ctx.arguments.id
            return User.Builder(ctx)
                .id(userId)
                .name("User-$userId")
                .build()
        }
    }

    // Resolver using queryValueFragment to access both object and query data
    @Resolver(
        objectValueFragment = "fragment _ on User { id }",
        queryValueFragment = "fragment _ on Query { viewer { name } }"
    )
    class User_DisplayNameResolver : UserResolvers.DisplayName() {
        override suspend fun resolve(ctx: Context): String {
            val userId = ctx.objectValue.getId()
            val viewerName = ctx.queryValue.getViewer()?.getName()
            return "$userId-displayedBy-$viewerName"
        }
    }

    // Resolver that handles null data from queryValueFragment gracefully
    @Resolver(
        objectValueFragment = "fragment _ on User { id }",
        queryValueFragment = "fragment _ on Query { viewerOrNull { name } }"
    )
    class User_DisplayNameFromNullViewerResolver : UserResolvers.DisplayNameFromNullViewer() {
        override suspend fun resolve(ctx: Context): String {
            val userId = ctx.objectValue.getId()
            val viewerName = ctx.queryValue.getViewerOrNull()?.getName() ?: "Unknown"
            return "$userId-displayedBy-$viewerName"
        }
    }

    // Resolver that depends on another Query Selections resolver (recursive dependency)
    @Resolver(
        objectValueFragment = "fragment _ on User { name }",
        queryValueFragment = "fragment _ on Query { viewer { id displayName } }"
    )
    class User_GreetingResolver : UserResolvers.Greeting() {
        override suspend fun resolve(ctx: Context): String {
            val userName = ctx.objectValue.getName()
            val viewerId = ctx.queryValue.getViewer()?.getId()
            val displayName = ctx.queryValue.getViewer()?.getDisplayName() ?: "UnknownViewer"
            return "Hello $userName, from $viewerId (displayed by $displayName)"
        }
    }

    // Mutation resolver that uses queryValueFragment to load selections on Query
    @Resolver(
        queryValueFragment = "fragment _ on Query { viewer { id name } user(id: \$userId) { id name } }",
        variables = [Variable(name = "userId", fromArgument = "userId")]
    )
    class Mutation_UpdateUserWithViewerInfoResolver : MutationResolvers.UpdateUserWithViewerInfo() {
        override suspend fun resolve(ctx: Context): UpdateResult {
            val userId = ctx.arguments.userId
            val viewer = ctx.queryValue.getViewer()
            val user = ctx.queryValue.getUser()

            val success = viewer != null && user != null
            val message = when {
                viewer == null -> "No viewer found"
                user == null -> "User $userId not found"
                else -> "Updated user ${user.getName()} (${user.getId()}) with info from viewer ${viewer.getName()} (${viewer.getId()})"
            }

            return UpdateResult.Builder(ctx)
                .success(success)
                .message(message)
                .build()
        }
    }

    @Test
    fun `core functionality - fetches and combines object and query data`() {
        // This test validates that required fields from the queryValueFragment (e.g., viewer.name)
        // are fetched implicitly even when not explicitly requested in the outer query
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
        // This test uses a resolver that queries a field (viewerOrNull) that returns null
        // to ensure the dependent resolver handles the null case without crashing
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
        // This test validates that a mutation field can use queryValueFragment to load
        // selections on Query, accessing both viewer data and user data with variables
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
