package viaduct.tenant.runtime.internal

import javax.inject.Provider
import viaduct.api.VariablesProvider

/**
 * Wrap a [VariablesProvider] with additional metadata that allows adapting to a [VariablesResolver]
 * @see [viaduct.tenant.runtime.execution.VariablesProviderExecutor]
 */
data class VariablesProviderInfo(val variables: Set<String>, val provider: Provider<out VariablesProvider<*>>) {
    // an empty companion object allows for companion extension methods
    companion object
}
