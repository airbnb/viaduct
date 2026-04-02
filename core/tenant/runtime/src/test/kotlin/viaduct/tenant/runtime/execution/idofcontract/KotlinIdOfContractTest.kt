@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.idofcontract

import kotlin.reflect.full.isSubclassOf
import viaduct.api.Resolver
import viaduct.api.globalid.GlobalID
import viaduct.tenant.runtime.execution.idofcontract.resolverbases.NodeResolvers
import viaduct.tenant.runtime.execution.idofcontract.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.idofcontract.resolverbases.UserResolvers
import viaduct.tenant.runtime.fixtures.IdOfContractTest

class KotlinIdOfContractTest : IdOfContractTest() {
    @Resolver
    class UserNodeResolver : NodeResolvers.User() {
        override suspend fun resolve(ctx: Context): User {
            val id = ctx.id
            val alice = ctx.globalIDFor(User.Reflection, "alice@yahoo.com")
            val bob = ctx.globalIDFor(User.Reflection, "bob@hotmail.com")
            return when (id.internalID) {
                "alice@yahoo.com" -> User.Builder(ctx).id(alice).name("Alice").cohostID(bob).build()
                "bob@hotmail.com" -> User.Builder(ctx).id(bob).name("Bob").cohostID(alice).build()
                else -> throw IllegalArgumentException("No User with id=$id")
            }
        }
    }

    @Resolver(" cohostID ")
    class User_CohostResolver : UserResolvers.Cohost() {
        override suspend fun resolve(ctx: Context): User {
            return ctx.nodeFor(ctx.objectValue.getCohostID()!!)
        }
    }

    @Resolver
    class Query_UserFromInputResolver : QueryResolvers.UserFromInput() {
        override suspend fun resolve(ctx: Context): User {
            return ctx.nodeFor(ctx.arguments.id!!.id)
        }
    }

    @Resolver
    class Query_UserFromArgumentResolver : QueryResolvers.UserFromArgument() {
        override suspend fun resolve(ctx: Context): User {
            return User.Builder(ctx)
                .id(ctx.arguments.id)
                .name("Alice")
                .build()
        }
    }

    @Resolver
    class Query_EntityFromIDResolver : QueryResolvers.EntityFromID() {
        override suspend fun resolve(ctx: Context): Entity {
            val id = ctx.arguments.id
            if (!id.type.kcls.isSubclassOf(Entity.Reflection.kcls)) throw IllegalArgumentException("Non-entity ID ($id)")
            if (id.type != User.Reflection) throw IllegalArgumentException("Can only handle user entities ($id)")
            @Suppress("UNCHECKED_CAST")
            return ctx.nodeFor(id as GlobalID<User>)
        }
    }
}
