package viaduct.engine.runtime.validation

fun interface Validator<in T> {
    /**
     * Validate the supplied value, returning Unit when valid and throwing an
     * exception when invalid.
     */
    fun validate(t: T)

    companion object {
        /** A [Validator] that always succeeds */
        val Unvalidated: Validator<Any?> = Validator {}

        /** A [Validator] that always throws */
        val Invalid: Validator<Any?> = Validator {
            throw IllegalArgumentException("Always invalid")
        }

        /**
         * Reduce a group of [Validator] into a single Validator.
         * The returned Validator will call each of the original validators
         * and will throw the first exception encountered
         */
        fun <T> Iterable<Validator<T>>.flatten(): Validator<T> =
            Validator { t ->
                this.forEach { it.validate(t) }
            }
    }
}
