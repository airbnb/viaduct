package viaduct.apiannotations

import kotlin.annotation.AnnotationRetention.BINARY
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.FUNCTION

/**
 * Marks a function or class as executing in *framework context*, even though it lives in a module
 * that defaults to tenant context (e.g. `tenant/api` or generated GRT code).
 *
 * Most code in `tenant/api` executes in tenant context — any unattributed exception thrown there
 * is attributed to tenant code. This annotation marks the exceptions: functions that are part of
 * the framework machinery and whose unattributed exceptions should be treated as framework bugs.
 *
 * **This annotation must not appear on functions that tenants call directly.** It must be paired
 * with `private`, `internal`, or [InternalApi].
 *
 * Any tenant-context function that calls an `@InFrameworkCode` function must ensure that
 * exceptions thrown from that call site are attributed to the framework, not tenant code.
 * Using a `handleFrameworkErrors` block is the standard way to achieve this.
 */
@Retention(BINARY)
@Target(CLASS, FUNCTION)
annotation class InFrameworkCode
