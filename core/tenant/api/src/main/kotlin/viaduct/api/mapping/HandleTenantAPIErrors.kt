package viaduct.api.mapping

import viaduct.errors.handleTenantAPIErrors as wrapTenantAPIErrors
import viaduct.mapping.graphql.Conv

/**
 * Returns a new [Conv] whose [Conv.invoke] and [Conv.invert] are both wrapped with
 * [viaduct.errors.handleTenantAPIErrors], attributing any non-[viaduct.errors.TenantException]
 * exceptions to the framework.
 */
internal fun <From, To> Conv<From, To>.handleTenantAPIErrors(message: String): Conv<From, To> =
    Conv(
        forward = { from -> wrapTenantAPIErrors(message) { invoke(from) } },
        inverse = { to -> wrapTenantAPIErrors(message) { invert(to) } },
    )
