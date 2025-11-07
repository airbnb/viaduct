package viaduct.engine.api

import graphql.language.Field

@Deprecated("For Airbnb use only, will be removed.")
interface TemporaryBypassAccessCheck {
    fun shouldBypassCheck(
        field: Field,
        bypassChecksDuringCompletion: Boolean
    ): Boolean

    /** A default implementation that only respect the given flag to bypass all checks,
     * but does not bypass individual check per field.
     * `bypassChecksDuringCompletion` is set to false by default, only overriden by shims.
     */
    @Suppress("DEPRECATION")
    object Default : TemporaryBypassAccessCheck {
        override fun shouldBypassCheck(
            field: Field,
            bypassChecksDuringCompletion: Boolean
        ): Boolean = bypassChecksDuringCompletion
    }
}
