package viaduct.api.resolver

import viaduct.apiannotations.StableApi

@StableApi
@Target(AnnotationTarget.CLASS)
annotation class Variables(
    /**
     * Each element describes one variable as `name: Type`.
     * All spaces are ignored.
     *
     * Example:
     * ```
     *   @Variables("foo: Int", "bar: String!")
     * ```
     */
    vararg val types: String
)
