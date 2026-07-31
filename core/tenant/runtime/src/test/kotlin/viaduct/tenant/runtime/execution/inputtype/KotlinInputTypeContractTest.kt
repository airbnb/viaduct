package viaduct.tenant.runtime.execution.inputtype

import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.inputtype.resolverbases.QueryResolvers

class KotlinInputTypeContractTest : InputTypeContractTest() {
    @Resolver
    class Query_UserByNameResolver : QueryResolvers.UserByName() {
        override suspend fun resolve(ctx: Context): User {
            val input = ctx.arguments.input
            return User.Builder(ctx)
                .name(input.name)
                .age(input.age)
                .balance(input.balance)
                .serial(input.serial)
                .build()
        }
    }
}
