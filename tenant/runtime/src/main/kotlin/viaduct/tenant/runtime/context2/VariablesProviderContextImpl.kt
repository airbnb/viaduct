package viaduct.tenant.runtime.context2

import viaduct.api.context.VariablesProviderContext
import viaduct.api.internal.InternalContext
import viaduct.api.types.Arguments

class VariablesProviderContextImpl<A : Arguments>(
    baseData: InternalContext,
    override val requestContext: Any?,
    override val args: A
) : VariablesProviderContext<A>, ExecutionContextImpl(baseData)
