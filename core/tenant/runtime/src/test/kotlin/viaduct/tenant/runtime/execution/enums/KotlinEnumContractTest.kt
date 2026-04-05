package viaduct.tenant.runtime.execution.enums

import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.enums.resolverbases.QueryResolvers

class KotlinEnumContractTest : EnumContractTest() {
    @Resolver
    class Query_CurrentStatusResolver : QueryResolvers.CurrentStatus() {
        override suspend fun resolve(ctx: Context): Status = Status.ACTIVE
    }
}
