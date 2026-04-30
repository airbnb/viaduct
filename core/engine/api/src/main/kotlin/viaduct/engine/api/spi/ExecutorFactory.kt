package viaduct.engine.api.spi

import viaduct.engine.api.bootstrap.executionregistry.FieldEntry
import viaduct.engine.api.bootstrap.executionregistry.NodeEntry

/**
 * Tenant API implementations provide this to the engine to create executors from registry entries.
 *
 * The engine calls these methods during bootstrapping; the Tenant API implementation is responsible
 * for constructing the executor from the config data.
 */
interface ExecutorFactory {
    fun createFieldResolverExecutor(configData: FieldEntry): FieldResolverExecutor

    fun createNodeResolverExecutor(configData: NodeEntry): NodeResolverExecutor
}
