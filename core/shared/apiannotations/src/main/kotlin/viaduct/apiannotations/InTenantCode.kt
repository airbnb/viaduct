package viaduct.apiannotations

import kotlin.annotation.AnnotationRetention.BINARY
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.FUNCTION

/**
 * Marks a function or class as executing in *tenant context*, even though it lives in a module
 * that defaults to framework context (e.g. `tenant/runtime`).
 *
 * Most code in `tenant/runtime` executes in framework context — any unattributed exception thrown
 * there is wrapped as a [viaduct.errors.FrameworkException]. This annotation marks the exceptions:
 * functions where an unattributed exception should instead be attributed to tenant code.
 *
 * Any framework-context function that calls an `@InTenantCode` function must ensure that
 * exceptions thrown from that call site are attributed to tenant code, not framework code.
 * Using a `handleTenantErrors` block is the standard way to achieve this.
 */
@Retention(BINARY)
@Target(CLASS, FUNCTION)
annotation class InTenantCode
