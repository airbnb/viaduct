@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.defaultresolverscontract

import viaduct.tenant.runtime.execution.defaultresolverscontract.resolverbases.NodeResolvers
import viaduct.tenant.runtime.fixtures.defaultquerynoderesolvercontract.DefaultQueryNodeResolverContractTest

class KotlinDefaultQueryNodeResolverContractTest : DefaultQueryNodeResolverContractTest() {
    class TestUserResolver : NodeResolvers.TestUser() {
        override suspend fun resolve(ctx: Context): TestUser {
            return TestUser.Builder(ctx).id(ctx.id).name("user name").build()
        }
    }
}
