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
    /**
     * Config-gated cutover: when true, node resolvers are proxied over the bidirectional
     * streaming transport instead of the unary one. Field resolvers are unaffected -- they
     * always use the unary transport, since streaming doesn't support fields yet.
     */
    val useStreamingTransport: Boolean = false,
    val rtsHost: String = DEFAULT_RTS_HOST,
    val rtsPort: Int = DEFAULT_RTS_PORT,
    val callbackPort: Int = DEFAULT_CALLBACK_PORT,
) {
    companion object {
        const val ENV_RTS_HOST = "VIADUCT_RTS_HOST"
        const val ENV_RTS_PORT = "VIADUCT_RTS_PORT"
        const val ENV_CALLBACK_PORT = "VIADUCT_RTS_CALLBACK_PORT"

        const val DEFAULT_RTS_HOST = "localhost"
        const val DEFAULT_RTS_PORT = 50051
        const val DEFAULT_CALLBACK_PORT = 50052

        fun fromEnvironment(
            env: EnvLookup = EnvLookup.SYSTEM,
            enabled: Boolean = false,
            useStreamingTransport: Boolean = false,
        ): RemoteResolverConfig =
            RemoteResolverConfig(
                enabled = enabled,
                useStreamingTransport = useStreamingTransport,
                rtsHost = env.get(ENV_RTS_HOST) ?: DEFAULT_RTS_HOST,
                rtsPort = env.get(ENV_RTS_PORT)?.toIntOrNull() ?: DEFAULT_RTS_PORT,
                callbackPort = env.get(ENV_CALLBACK_PORT)?.toIntOrNull() ?: DEFAULT_CALLBACK_PORT,
            )
    }
}
