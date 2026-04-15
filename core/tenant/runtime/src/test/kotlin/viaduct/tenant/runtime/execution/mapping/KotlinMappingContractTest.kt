@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.mapping

import java.time.LocalDate
import java.time.Month
import viaduct.api.Resolver
import viaduct.api.context.nodeRef
import viaduct.api.mapping.GRTDomain
import viaduct.api.mapping.JsonDomain
import viaduct.tenant.runtime.execution.mapping.resolverbases.NodeResolvers
import viaduct.tenant.runtime.execution.mapping.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.mapping.resolverbases.UserResolvers

class KotlinMappingContractTest : MappingContractTest() {
    @Resolver
    class UserNodeResolver : NodeResolvers.User() {
        override suspend fun resolve(ctx: Context): User =
            User.Builder(ctx)
                .id(ctx.globalIDFor(User.Reflection, "1"))
                .name("Frodo Baggins")
                .dob(LocalDate.of(1954, Month.SEPTEMBER, 22))
                .build()
    }

    @Resolver("fragment _ on User { dob }")
    class UserBirthYearResolver : UserResolvers.BirthYear() {
        override suspend fun resolve(ctx: Context): Int = ctx.objectValue.getDob().year
    }

    @Resolver
    class QueryUserResolver : QueryResolvers.User() {
        override suspend fun resolve(ctx: Context): User = ctx.nodeRef("1")
    }

    @Resolver
    class QuerySyncGrtToJsonResolver : QueryResolvers.SyncGrtToJson() {
        override suspend fun resolve(ctx: Context): String {
            val mapper = GRTDomain(ctx).mapperTo(JsonDomain(ctx))
            val user = User.Builder(ctx)
                .id(ctx.globalIDFor(User.Reflection, "1"))
                .name("Frodo Baggins")
                .dob(LocalDate.of(1954, Month.SEPTEMBER, 22))
                .birthYear(1954)
                .build()
            return mapper(user)
        }
    }

    @Resolver
    class QueryInputJsonToGrtResolver : QueryResolvers.InputJsonToGrt() {
        override suspend fun resolve(ctx: Context): User {
            val mapper = JsonDomain.forType(ctx, User.Reflection).mapperTo(GRTDomain(ctx))
            return mapper(ctx.arguments.json) as User
        }
    }
}
