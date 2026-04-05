@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.scopes

import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.scopes.resolverbases.QueryResolvers

class KotlinScopesContractTest : ScopesContractTest() {
    @Resolver
    class Scope1ValueResolver : QueryResolvers.Scope1Value() {
        override suspend fun resolve(ctx: Context): TestScope1Object {
            return TestScope1Object.Builder(ctx).strValue("scope 1 value").build()
        }
    }

    @Resolver
    class Scope2ValueResolver : QueryResolvers.Scope2Value() {
        override suspend fun resolve(ctx: Context): TestScope2Object {
            return TestScope2Object.Builder(ctx).strValue("scope 2 value").build()
        }
    }
}
