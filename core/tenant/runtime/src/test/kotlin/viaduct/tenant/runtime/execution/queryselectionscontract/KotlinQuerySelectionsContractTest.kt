@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.queryselectionscontract

import viaduct.api.Resolver
import viaduct.api.Variable
import viaduct.tenant.runtime.execution.queryselectionscontract.resolverbases.MutationResolvers
import viaduct.tenant.runtime.execution.queryselectionscontract.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.queryselectionscontract.resolverbases.UserResolvers
import viaduct.tenant.runtime.fixtures.queryselectionscontract.QuerySelectionsContractTest

class KotlinQuerySelectionsContractTest : QuerySelectionsContractTest() {
    @Resolver
    class Query_ViewerResolver : QueryResolvers.Viewer() {
        override suspend fun resolve(ctx: Context): User {
            return User.Builder(ctx)
                .id("viewer-123")
                .name("ViewerUser")
                .build()
        }
    }

    @Resolver
    class Query_ViewerOrNullResolver : QueryResolvers.ViewerOrNull() {
        override suspend fun resolve(ctx: Context): User? {
            return null
        }
    }

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
}
