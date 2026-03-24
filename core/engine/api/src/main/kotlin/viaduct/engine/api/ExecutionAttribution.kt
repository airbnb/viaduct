package viaduct.engine.api

/**
 * Represents the attribution of a required selection set or a query plan to a specific source.
 *
 * @property type The type of attribution.
 * @property name An optional name associated with the attribution.
 */
data class ExecutionAttribution private constructor(
    val type: Type?,
    val name: String?
) {
    enum class Type {
        /** user defined operation */
        OPERATION,

        /** from a field resolver */
        RESOLVER,

        /** from a policy check */
        POLICY_CHECK,

        /** from a variables resolver */
        VARIABLES_RESOLVER
    }

    companion object {
        val DEFAULT: ExecutionAttribution = ExecutionAttribution(null, null)

        fun fromOperation(name: String?): ExecutionAttribution = name?.let { ExecutionAttribution(Type.OPERATION, name) } ?: DEFAULT

        fun fromResolver(name: String): ExecutionAttribution = ExecutionAttribution(Type.RESOLVER, name)

        fun fromPolicyCheck(name: String): ExecutionAttribution = ExecutionAttribution(Type.POLICY_CHECK, name)

        fun fromVariablesResolver(name: String): ExecutionAttribution = ExecutionAttribution(Type.VARIABLES_RESOLVER, name)
    }

    /**
     * Converts the attribution to a tag string in the format "type:name".
     */
    fun toTagString(): String? {
        return if (type != null && name != null) {
            "$type:$name"
        } else {
            null
        }
    }
}
