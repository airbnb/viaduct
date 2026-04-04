package viaduct.tenant.runtime.execution.inputtypecontract

import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.inputtypecontract.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.inputtypecontract.InputTypeContractTest

class KotlinInputTypeContractTest : InputTypeContractTest() {
    @Resolver
    class Query_UserByNameResolver : QueryResolvers.UserByName() {
        override suspend fun resolve(ctx: Context): User {
            val input = ctx.arguments.input
            return User.Builder(ctx).name(input.name).age(input.age).build()
        }
    }
}
