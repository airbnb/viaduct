package viaduct.remote.config

/** Reads a single environment variable. Abstracted so tests and DI-based hosts can supply their own source. */
fun interface EnvLookup {
    fun get(name: String): String?

    companion object {
        @Suppress("SystemGetEnv")
        val SYSTEM: EnvLookup = EnvLookup { System.getenv(it) }
    }
}

/** Transport mode for remote resolver execution. */
enum class RemoteResolverMode {
    IN_PROCESS,
    NETWORK,
}

/**
 * Configuration for remote resolver execution. Use [fromEnvironment] for the standard
 * env-driven flow, or the primary constructor for explicit wiring.
 */
data class RemoteResolverConfig(
    val enabled: Boolean,
    val mode: RemoteResolverMode = RemoteResolverMode.IN_PROCESS,
    val remoteTypes: Set<String>,
    val remoteFields: Set<String> = emptySet(),
    val rrsServerName: String = DEFAULT_RRS_SERVER_NAME,
    val callbackEndpoint: String = DEFAULT_CALLBACK_ENDPOINT,
    val rrsHost: String = DEFAULT_RRS_HOST,
    val rrsPort: Int = DEFAULT_RRS_PORT,
    val callbackPort: Int = DEFAULT_CALLBACK_PORT,
) {
    companion object {
        const val ENV_ENABLED = "VIADUCT_REMOTE_RESOLVER_ENABLED"
        const val ENV_MODE = "VIADUCT_REMOTE_RESOLVER_MODE"
        const val ENV_TYPES = "VIADUCT_REMOTE_RESOLVER_TYPES"
        const val ENV_FIELDS = "VIADUCT_REMOTE_RESOLVER_FIELDS"
        const val ENV_RRS_HOST = "VIADUCT_RRS_HOST"
        const val ENV_RRS_PORT = "VIADUCT_RRS_PORT"
        const val ENV_CALLBACK_PORT = "VIADUCT_RRS_CALLBACK_PORT"

        const val DEFAULT_RRS_SERVER_NAME = "viaduct-rrs-inprocess"
        const val DEFAULT_CALLBACK_ENDPOINT = "viaduct-rrp-callback"
        const val DEFAULT_RRS_HOST = "localhost"
        const val DEFAULT_RRS_PORT = 50051
        const val DEFAULT_CALLBACK_PORT = 50052

        fun fromEnvironment(env: EnvLookup = EnvLookup.SYSTEM): RemoteResolverConfig =
            RemoteResolverConfig(
                enabled = parseEnabled(env.get(ENV_ENABLED)),
                mode = parseMode(env.get(ENV_MODE)),
                remoteTypes = parseTypes(env.get(ENV_TYPES)),
                remoteFields = parseTypes(env.get(ENV_FIELDS)),
                rrsHost = env.get(ENV_RRS_HOST) ?: DEFAULT_RRS_HOST,
                rrsPort = env.get(ENV_RRS_PORT)?.toIntOrNull() ?: DEFAULT_RRS_PORT,
                callbackPort = env.get(ENV_CALLBACK_PORT)?.toIntOrNull() ?: DEFAULT_CALLBACK_PORT,
            )

        // Strict-boolean parse: `"1"` / `"yes"` / blank / unparseable all fall through to
        // `false`, so opt-in requires the literal `"true"`.
        private fun parseEnabled(raw: String?): Boolean = raw?.takeIf { it.isNotBlank() }?.toBooleanStrictOrNull() ?: false

        private fun parseMode(raw: String?): RemoteResolverMode =
            when (raw?.lowercase()) {
                "network" -> RemoteResolverMode.NETWORK
                "in_process", "inprocess" -> RemoteResolverMode.IN_PROCESS
                else -> RemoteResolverMode.IN_PROCESS
            }

        private fun parseTypes(raw: String?): Set<String> =
            raw?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
    }
}
