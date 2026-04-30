package viaduct.apiannotations

import kotlin.annotation.AnnotationRetention.BINARY
import kotlin.annotation.AnnotationTarget.FUNCTION

/**
 * Declares the error-attribution execution context for a function, overriding the
 * module-default context.
 *
 * Most code in `tenant/runtime` defaults to framework context; most code in `tenant/api`
 * defaults to tenant context. This annotation marks the exceptions where a function's
 * attribution differs from its module default.
 *
 * Any function that calls across an attribution boundary must ensure exceptions from that
 * call site are attributed correctly — typically via `handleTenantErrors` or
 * `handleFrameworkErrors` blocks.
 */
@Retention(BINARY)
@Target(FUNCTION)
annotation class Attribution(val value: AttributionContext)
