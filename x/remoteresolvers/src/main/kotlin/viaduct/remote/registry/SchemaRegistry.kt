package viaduct.remote.registry

import org.slf4j.LoggerFactory
import viaduct.engine.api.ViaductSchema

/**
 * Holds the schema [RemoteEngineExecutionContext] reads in NETWORK mode (no delegate
 * available). Single writer at startup; readers see the publish via the volatile.
 */
object SchemaRegistry {
    private val log = LoggerFactory.getLogger(SchemaRegistry::class.java)

    @Volatile
    private var schema: ViaductSchema? = null

    fun register(viaductSchema: ViaductSchema) {
        schema = viaductSchema
        log.info("Registered schema")
    }

    fun get(): ViaductSchema? = schema

    fun isRegistered(): Boolean = schema != null

    /** Test-only reset hook. */
    fun clear() {
        schema = null
    }
}
