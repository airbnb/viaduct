package viaduct.remote.config

/** Reads a single environment variable. Abstracted so tests and DI-based hosts can supply their own source. */
fun interface EnvLookup {
    fun get(name: String): String?

    companion object {
        @Suppress("SystemGetEnv")
        val SYSTEM: EnvLookup = EnvLookup { System.getenv(it) }
    }
}

/**
 * Configuration for remote resolver execution. Use [fromEnvironment] for environment-backed
 * resolver details and supply [enabled] from the caller, or use the primary constructor for
 * explicit wiring.
 */
data class RemoteResolverConfig(
    val enabled: Boolean,
    val remoteTypes: Set<String>,
    val remoteFields: Set<String> = emptySet(),
    /** When false, no field resolvers are proxied even though nodes still are (the field-only off switch). */
    val fieldProxyingEnabled: Boolean = true,
    val rrsHost: String = DEFAULT_RRS_HOST,
    val rrsPort: Int = DEFAULT_RRS_PORT,
    val callbackPort: Int = DEFAULT_CALLBACK_PORT,
) {
    companion object {
        const val ENV_TYPES = "VIADUCT_REMOTE_RESOLVER_TYPES"
        const val ENV_FIELDS = "VIADUCT_REMOTE_RESOLVER_FIELDS"
        const val ENV_RRS_HOST = "VIADUCT_RRS_HOST"
        const val ENV_RRS_PORT = "VIADUCT_RRS_PORT"
        const val ENV_CALLBACK_PORT = "VIADUCT_RRS_CALLBACK_PORT"

        const val DEFAULT_RRS_HOST = "localhost"
        const val DEFAULT_RRS_PORT = 50051
        const val DEFAULT_CALLBACK_PORT = 50052

        // Sentinel values for ENV_FIELDS that turn field proxying fully off (node proxying stays on) —
        // the first-class rollback for the default-on flip, distinct from empty (= all fields).
        private val FIELDS_OFF_SENTINELS = setOf("none", "off", "-")

        fun fromEnvironment(
            env: EnvLookup = EnvLookup.SYSTEM,
            enabled: Boolean = false,
        ): RemoteResolverConfig {
            val fieldsRaw = env.get(ENV_FIELDS)
            val fieldProxyingOff = fieldsRaw?.trim()?.lowercase() in FIELDS_OFF_SENTINELS
            return RemoteResolverConfig(
                enabled = enabled,
                remoteTypes = parseTypes(env.get(ENV_TYPES)),
                remoteFields = if (fieldProxyingOff) emptySet() else parseTypes(fieldsRaw),
                fieldProxyingEnabled = !fieldProxyingOff,
                rrsHost = env.get(ENV_RRS_HOST) ?: DEFAULT_RRS_HOST,
                rrsPort = env.get(ENV_RRS_PORT)?.toIntOrNull() ?: DEFAULT_RRS_PORT,
                callbackPort = env.get(ENV_CALLBACK_PORT)?.toIntOrNull() ?: DEFAULT_CALLBACK_PORT,
            )
        }

        private fun parseTypes(raw: String?): Set<String> =
            raw?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
    }
}
