package viaduct.api.mapping

import viaduct.apiannotations.Attribution
import viaduct.apiannotations.AttributionContext
import viaduct.errors.handleFrameworkErrors
import viaduct.mapping.graphql.Conv

/**
 * Returns a new [Conv] whose [Conv.invoke] and [Conv.invert] are both wrapped with
 * [viaduct.errors.handleFrameworkErrors], attributing any non-[viaduct.errors.TenantException]
 * exceptions to the framework.
 */
internal fun <From, To> Conv<From, To>.handleFrameworkErrors(message: String): Conv<From, To> =
    Conv(
        forward = { from -> handleFrameworkErrors(message) { applyForward(this, from) } },
        inverse = { to -> handleFrameworkErrors(message) { applyInverse(this, to) } },
    )

@Attribution(AttributionContext.FRAMEWORK)
private fun <From, To> applyForward(
    conv: Conv<From, To>,
    from: From
): To = conv(from)

@Attribution(AttributionContext.FRAMEWORK)
private fun <From, To> applyInverse(
    conv: Conv<From, To>,
    to: To
): From = conv.invert(to)
