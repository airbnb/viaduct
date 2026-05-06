package viaduct.remote.config

/** Reads a single environment variable; abstracted so callers can override (tests, host integration). */
fun interface EnvLookup {
    fun get(name: String): String?

    companion object {
        /** Default lookup backed by the JVM process environment. */
        // Single, well-marked use of System.getenv. The lookup is exposed via [EnvLookup]
        // so callers (tests, services that thread env through their own config layer)
        // can substitute a different source without touching this module.
        @Suppress("SystemGetEnv")
        val SYSTEM: EnvLookup = EnvLookup { System.getenv(it) }
    }
}

/**
 * Configuration for remote resolver execution.
 *
 * Construct via [fromEnvironment] for the standard env-driven flow, or call the
 * primary constructor directly for tests and explicit configuration.
 *
 * @property enabled whether remote resolver execution is enabled (defaults to `false`;
 *   opt in via `VIADUCT_REMOTE_RESOLVER_ENABLED=true`)
 * @property remoteTypes GraphQL type names to proxy; empty means all types
 */
data class RemoteResolverConfig(
    val enabled: Boolean,
    val remoteTypes: Set<String>,
    val rrsServerName: String = DEFAULT_RRS_SERVER_NAME,
    val callbackEndpoint: String = DEFAULT_CALLBACK_ENDPOINT,
) {
    companion object {
        const val ENV_ENABLED = "VIADUCT_REMOTE_RESOLVER_ENABLED"
        const val ENV_TYPES = "VIADUCT_REMOTE_RESOLVER_TYPES"
        const val DEFAULT_RRS_SERVER_NAME = "viaduct-rrs-inprocess"
        const val DEFAULT_CALLBACK_ENDPOINT = "viaduct-rrp-callback"

        /** Reads [ENV_ENABLED] and [ENV_TYPES] from [env]; defaults to the JVM process environment. */
        fun fromEnvironment(env: EnvLookup = EnvLookup.SYSTEM): RemoteResolverConfig =
            RemoteResolverConfig(
                enabled = parseEnabled(env.get(ENV_ENABLED)),
                remoteTypes = parseTypes(env.get(ENV_TYPES)),
            )

        // Off by default — opt-in via `VIADUCT_REMOTE_RESOLVER_ENABLED=true`. Unset, blank,
        // or unparseable values all fall through to `false`; using `toBooleanStrictOrNull`
        // means a literal `"1"`/`"yes"` doesn't accidentally enable the feature.
        private fun parseEnabled(raw: String?): Boolean = raw?.takeIf { it.isNotBlank() }?.toBooleanStrictOrNull() ?: false

        private fun parseTypes(raw: String?): Set<String> =
            raw?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
    }
}
