package viaduct.api.mapping

import viaduct.errors.handleFrameworkErrors
import viaduct.mapping.graphql.Conv

/**
 * Returns a new [Conv] whose [Conv.invoke] and [Conv.invert] are both wrapped with
 * [viaduct.errors.handleFrameworkErrors], attributing any non-[viaduct.errors.TenantException]
 * exceptions to the framework.
 */
internal fun <From, To> Conv<From, To>.handleFrameworkErrors(message: String): Conv<From, To> =
    Conv(
        forward = { from -> handleFrameworkErrors(message) { invoke(from) } },
        inverse = { to -> handleFrameworkErrors(message) { invert(to) } },
    )
