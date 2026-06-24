package com.example.remote

import org.slf4j.LoggerFactory
import viaduct.remote.config.EnvLookup

/**
 * Configuration for the standalone remote resolver server.
 *
 * @property port port to listen on for incoming gRPC requests
 * @property callbackHost hostname the main (proxy) callback server is reachable at
 * @property callbackPort port the main (proxy) callback server listens on
 */
data class RemoteConfiguration(
    val port: Int = DEFAULT_PORT,
    val callbackHost: String = DEFAULT_CALLBACK_HOST,
    val callbackPort: Int = DEFAULT_CALLBACK_PORT
) {
    companion object {
        const val DEFAULT_PORT = 50051
        const val DEFAULT_CALLBACK_PORT = 50052
        const val DEFAULT_CALLBACK_HOST = "localhost"

        const val ENV_PORT = "VIADUCT_RRS_PORT"
        const val ENV_CALLBACK_HOST = "VIADUCT_RRS_CALLBACK_HOST"
        const val ENV_CALLBACK_PORT = "VIADUCT_RRS_CALLBACK_PORT"

        /**
         * Parses CLI arguments. Args override environment defaults; any unset arg falls
         * back to [env] (or the compile-time default if unset there too).
         */
        fun fromArgs(
            args: Array<String>,
            env: EnvLookup = EnvLookup.SYSTEM
        ): RemoteConfiguration {
            val base = fromEnvironment(env)
            var port = base.port
            var callbackHost = base.callbackHost
            var callbackPort = base.callbackPort

            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--port" -> {
                        port = parsePort(args.getOrNull(i + 1), "--port", port)
                        i += 2
                    }
                    "--callback-host" -> {
                        callbackHost = args.getOrNull(i + 1) ?: callbackHost
                        i += 2
                    }
                    "--callback-port" -> {
                        callbackPort = parsePort(args.getOrNull(i + 1), "--callback-port", callbackPort)
                        i += 2
                    }
                    else -> i++
                }
            }
            return RemoteConfiguration(port, callbackHost, callbackPort)
        }

        private fun parsePort(
            raw: String?,
            flag: String,
            fallback: Int
        ): Int {
            val parsed = raw?.toIntOrNull()
            if (raw != null && parsed == null) {
                log.warn("{} value '{}' is not an integer; using {}", flag, raw, fallback)
            }
            return parsed ?: fallback
        }

        private val log = LoggerFactory.getLogger(RemoteConfiguration::class.java)

        fun fromEnvironment(env: EnvLookup = EnvLookup.SYSTEM): RemoteConfiguration =
            RemoteConfiguration(
                port = env.get(ENV_PORT)?.toIntOrNull() ?: DEFAULT_PORT,
                callbackHost = env.get(ENV_CALLBACK_HOST) ?: DEFAULT_CALLBACK_HOST,
                callbackPort = env.get(ENV_CALLBACK_PORT)?.toIntOrNull() ?: DEFAULT_CALLBACK_PORT
            )
    }
}
