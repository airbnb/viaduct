@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.queryselections

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals as assertSelectedFields
import org.junit.jupiter.api.Test
import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.QueryFromAnnotation
import viaduct.api.resolver.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.execution.queryselections.resolverbases.MutationResolvers
import viaduct.tenant.runtime.execution.queryselections.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.queryselections.resolverbases.UserResolvers

@GraphQLOperation("query(\$userId: ID!) { viewer { id name } user(id: \$userId) { id name } }")
object ViewerAndUserQuery : QueryFromAnnotation()

@GraphQLOperation("query(\$id: ID!) { user(id: \$id) { id name } }")
object UserFieldsQuery : QueryFromAnnotation()

@GraphQLOperation("query(\$id: ID!) { user(id: \$id) { id name upperName } }")
object UserWithUpperNameQuery : QueryFromAnnotation()

class KotlinQuerySelectionsContractTest : QuerySelectionsContractTest() {
    companion object {
        private val observedSelections = AtomicReference<Set<String>?>()
        private val observedRssName = AtomicReference<String?>()
        private val observedSelectionsById = ConcurrentHashMap<String, Set<String>>()
    }

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

    @Resolver
    class Query_CopiedUserResolver : QueryResolvers.CopiedUser() {
        override suspend fun resolve(ctx: Context): User? {
            val selections = ctx.selections()
            val selectedFields = buildSet {
                if (selections.contains(User.Fields.id)) add("id")
                if (selections.contains(User.Fields.name)) add("name")
                if (selections.contains(User.Fields.upperName)) add("upperName")
            }
            observedSelections.set(selectedFields)
            observedSelectionsById[ctx.arguments.id] = selectedFields
            val operation = if (selections.contains(User.Fields.upperName)) UserWithUpperNameQuery else UserFieldsQuery
            return ctx.query(operation, mapOf("id" to ctx.arguments.id)).getUserOrThrow()
        }
    }

    @Resolver("rssUser: copiedUser(id: \"rss-only\") { upperName }")
    class Query_UpperNameFromRssResolver : QueryResolvers.UpperNameFromRss() {
        override suspend fun resolve(ctx: Context): String? = ctx.getObjectValue().getCopiedUserOrThrow("rssUser")?.getUpperNameOrThrow()
    }

    @Resolver("name")
    class User_UpperNameResolver : UserResolvers.UpperName() {
        override suspend fun resolve(ctx: Context): String {
            val name = ctx.getObjectValue().getNameOrThrow()
            observedRssName.set(name)
            return "$name".uppercase()
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

    @Resolver
    class Mutation_UpdateUserWithViewerInfoResolver : MutationResolvers.UpdateUserWithViewerInfo() {
        override suspend fun resolve(ctx: Context): UpdateResult {
            val userId = ctx.arguments.userId
            val result = ctx.query(ViewerAndUserQuery, mapOf("userId" to userId))
            val viewer = result.getViewerOrThrow()
            val user = result.getUserOrThrow()

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

    @Test
    fun `query derived GRT returns directly selected field`() {
        observedSelections.set(null)
        execute("{ copiedUser(id: \"direct\") { id } }").assertEquals {
            "data" to { "copiedUser" to { "id" to "direct" } }
        }
        assertSelectedFields(setOf("id"), observedSelections.get())
    }

    @Test
    fun `RSS supplies name when client selects only upperName`() {
        observedRssName.set(null)
        execute("{ user(id: \"source\") { upperName } }").assertEquals {
            "data" to { "user" to { "upperName" to "USER-SOURCE" } }
        }
        assertSelectedFields("User-source", observedRssName.get())
    }

    @Test
    fun `upstream RSS selections reach query derived GRT resolver`() {
        observedSelections.set(null)
        observedRssName.set(null)
        execute("{ upperNameFromRss }").assertEquals {
            "data" to { "upperNameFromRss" to "USER-RSS-ONLY" }
        }
        assertSelectedFields(setOf("upperName"), observedSelections.get())
        assertSelectedFields("User-rss-only", observedRssName.get())
    }

    @Test
    fun `each query derived GRT receives selections for its output position`() {
        observedSelectionsById.clear()
        execute("{ first: copiedUser(id: \"first\") { id } second: copiedUser(id: \"second\") { upperName } }").assertEquals {
            "data" to {
                "first" to { "id" to "first" }
                "second" to { "upperName" to "USER-SECOND" }
            }
        }
        assertSelectedFields(setOf("id"), observedSelectionsById["first"])
        assertSelectedFields(setOf("upperName"), observedSelectionsById["second"])
    }

    @Test
    fun `query derived GRT resolves RSS field selected without its source`() {
        observedSelections.set(null)
        execute("{ copiedUser(id: \"rss\") { upperName } }").assertEquals {
            "data" to { "copiedUser" to { "upperName" to "USER-RSS" } }
        }
        assertSelectedFields(setOf("upperName"), observedSelections.get())
    }

    @Test
    fun `query derived GRT exposes explicitly selected RSS source`() {
        observedSelections.set(null)
        execute("{ copiedUser(id: \"explicit\") { name upperName } }").assertEquals {
            "data" to {
                "copiedUser" to {
                    "name" to "User-explicit"
                    "upperName" to "USER-EXPLICIT"
                }
            }
        }
        assertSelectedFields(setOf("name", "upperName"), observedSelections.get())
    }

    @Test
    fun `query derived GRT resolves aliased RSS field without selecting its source`() {
        observedSelections.set(null)
        execute("{ copiedUser(id: \"alias\") { renamed: upperName } }").assertEquals {
            "data" to { "copiedUser" to { "renamed" to "USER-ALIAS" } }
        }
        assertSelectedFields(setOf("upperName"), observedSelections.get())
    }
}
