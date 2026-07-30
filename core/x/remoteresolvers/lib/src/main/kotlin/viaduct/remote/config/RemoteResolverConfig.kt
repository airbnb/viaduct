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
 * transport details and supply [enabled] from the caller, or use the primary constructor.
 */
data class RemoteResolverConfig(
    val enabled: Boolean,
    val rrsHost: String = DEFAULT_RRS_HOST,
    val rrsPort: Int = DEFAULT_RRS_PORT,
    val callbackPort: Int = DEFAULT_CALLBACK_PORT,
) {
    companion object {
        const val ENV_RRS_HOST = "VIADUCT_RRS_HOST"
        const val ENV_RRS_PORT = "VIADUCT_RRS_PORT"
        const val ENV_CALLBACK_PORT = "VIADUCT_RRS_CALLBACK_PORT"

        const val DEFAULT_RRS_HOST = "localhost"
        const val DEFAULT_RRS_PORT = 50051
        const val DEFAULT_CALLBACK_PORT = 50052

        fun fromEnvironment(
            env: EnvLookup = EnvLookup.SYSTEM,
            enabled: Boolean = false,
        ): RemoteResolverConfig =
            RemoteResolverConfig(
                enabled = enabled,
                rrsHost = env.get(ENV_RRS_HOST) ?: DEFAULT_RRS_HOST,
                rrsPort = env.get(ENV_RRS_PORT)?.toIntOrNull() ?: DEFAULT_RRS_PORT,
                callbackPort = env.get(ENV_CALLBACK_PORT)?.toIntOrNull() ?: DEFAULT_CALLBACK_PORT,
            )
    }
}
