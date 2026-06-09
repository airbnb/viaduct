package viaduct.engine.api.spi

import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig

/**
 * Tenant API implementations provide this to the engine to create executors from registry entries.
 *
 * The engine calls these methods during bootstrapping; the Tenant API implementation is responsible
 * for constructing the executor from the config data.
 *
 * The schema parameter is a temporary addition that will be removed once all executor factories
 * are fully schema-independent.
 */
interface ExecutorFactory {
    fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: ViaductSchema
    ): FieldResolverExecutor

    fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: ViaductSchema
    ): NodeResolverExecutor
}
