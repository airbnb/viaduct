@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.queryselections

import viaduct.api.resolver.Resolver
import viaduct.api.resolver.Variable
import viaduct.tenant.runtime.execution.queryselections.resolverbases.MutationResolvers
import viaduct.tenant.runtime.execution.queryselections.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.queryselections.resolverbases.UserResolvers

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
    class Query_NullableViewerResolver : QueryResolvers.NullableViewer() {
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
            val userId = ctx.getObjectValue().getIdOrThrow()
            val viewerName = ctx.getQueryValue().getViewerOrThrow()?.getNameOrThrow()
            return "$userId-displayedBy-$viewerName"
        }
    }

    @Resolver(
        objectValueFragment = "fragment _ on User { id }",
        queryValueFragment = "fragment _ on Query { nullableViewer { name } }"
    )
    class User_DisplayNameFromNullViewerResolver : UserResolvers.DisplayNameFromNullViewer() {
        override suspend fun resolve(ctx: Context): String {
            val userId = ctx.getObjectValue().getIdOrThrow()
            val viewerName = ctx.getQueryValue().getNullableViewerOrThrow()?.getNameOrThrow() ?: "Unknown"
            return "$userId-displayedBy-$viewerName"
        }
    }

    @Resolver(
        objectValueFragment = "fragment _ on User { name }",
        queryValueFragment = "fragment _ on Query { viewer { id displayName } }"
    )
    class User_GreetingResolver : UserResolvers.Greeting() {
        override suspend fun resolve(ctx: Context): String {
            val userName = ctx.getObjectValue().getNameOrThrow()
            val viewerId = ctx.getQueryValue().getViewerOrThrow()?.getIdOrThrow()
            val displayName = ctx.getQueryValue().getViewerOrThrow()?.getDisplayNameOrThrow() ?: "UnknownViewer"
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
            val viewer = ctx.getQueryValue().getViewerOrThrow()
            val user = ctx.getQueryValue().getUserOrThrow()

            val success = viewer != null && user != null
            val message = when {
                viewer == null -> "No viewer found"
                user == null -> "User $userId not found"
                else -> "Updated user ${user.getNameOrThrow()} (${user.getIdOrThrow()}) with info from viewer ${viewer.getNameOrThrow()} (${viewer.getIdOrThrow()})"
            }

            return UpdateResult.Builder(ctx)
                .success(success)
                .message(message)
                .build()
        }
    }
}
