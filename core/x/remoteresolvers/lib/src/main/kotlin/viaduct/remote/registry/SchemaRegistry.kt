package viaduct.remote.registry

import org.slf4j.LoggerFactory
import viaduct.engine.api.EngineSchema

/**
 * Holds the schema [RemoteEngineExecutionContext] reads when no delegate is available.
 * Single writer at startup; readers see the publish via the volatile.
 */
object SchemaRegistry {
    private val log = LoggerFactory.getLogger(SchemaRegistry::class.java)

    @Volatile
    private var schema: EngineSchema? = null

    fun register(viaductSchema: EngineSchema) {
        schema = viaductSchema
        log.info("Registered schema")
    }

    fun get(): EngineSchema? = schema

    fun isRegistered(): Boolean = schema != null

    /** Test-only reset hook. */
    fun clear() {
        schema = null
    }
}
