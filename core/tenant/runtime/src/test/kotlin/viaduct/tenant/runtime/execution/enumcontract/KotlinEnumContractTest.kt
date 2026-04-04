package viaduct.tenant.runtime.execution.enumcontract

import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.enumcontract.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.enumcontract.EnumContractTest

class KotlinEnumContractTest : EnumContractTest() {
    @Resolver
    class Query_CurrentStatusResolver : QueryResolvers.CurrentStatus() {
        override suspend fun resolve(ctx: Context): Status = Status.ACTIVE
    }
}
