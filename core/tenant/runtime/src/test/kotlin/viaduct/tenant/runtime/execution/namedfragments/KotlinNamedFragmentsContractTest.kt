@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.namedfragments

import viaduct.api.documents.FragmentFromAnnotation
import viaduct.api.documents.GraphQLFragment
import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.namedfragments.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.namedfragments.resolverbases.UserResolvers

// Named fragment definitions — discovered at bootstrap time via @GraphQLFragment scanning.
// These fragments are spread into resolver objectValueFragment / queryValueFragment strings below.

@GraphQLFragment("fragment UserCoreFields on User { id name }")
object UserCoreFieldsFragment : FragmentFromAnnotation<User>()

@GraphQLFragment("fragment ViewerNameFields on Query { viewer { name } }")
object ViewerNameFieldsFragment : FragmentFromAnnotation<Query>()

class KotlinNamedFragmentsContractTest : NamedFragmentsContractTest() {
    @Resolver
    class Query_UserResolver : QueryResolvers.User() {
        override suspend fun resolve(ctx: Context): User {
            val id = ctx.arguments.id
            return User.Builder(ctx).id(id).name("User-$id").build()
        }
    }

    @Resolver
    class Query_ViewerResolver : QueryResolvers.Viewer() {
        override suspend fun resolve(ctx: Context): User {
            return User.Builder(ctx).id("viewer-42").name("ViewerUser").build()
        }
    }

    // Spreads the named fragment UserCoreFields to read id and name from the object.
    @Resolver(objectValueFragment = "fragment Main on User { ...UserCoreFields }")
    class User_LabelResolver : UserResolvers.Label() {
        override suspend fun resolve(ctx: Context): String {
            val id = ctx.getObjectValue().getId()
            val name = ctx.getObjectValue().getName()
            return "$id:$name"
        }
    }

    // Spreads the named fragment ViewerNameFields to read viewer name from the root query.
    @Resolver(queryValueFragment = "fragment Main on Query { ...ViewerNameFields }")
    class User_GreetingResolver : UserResolvers.Greeting() {
        override suspend fun resolve(ctx: Context): String {
            val viewerName = ctx.getQueryValue().getViewer()?.getName()
            return "$viewerName-greeting"
        }
    }
}
