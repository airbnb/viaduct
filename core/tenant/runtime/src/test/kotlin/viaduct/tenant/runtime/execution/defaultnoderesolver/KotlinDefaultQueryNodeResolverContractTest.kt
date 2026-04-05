@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.defaultnoderesolver

import viaduct.tenant.runtime.execution.defaultnoderesolver.resolverbases.NodeResolvers

class KotlinDefaultQueryNodeResolverContractTest : DefaultQueryNodeResolverContractTest() {
    class TestUserResolver : NodeResolvers.TestUser() {
        override suspend fun resolve(ctx: Context): TestUser {
            return TestUser.Builder(ctx).id(ctx.id).name("user name").build()
        }
    }
}
