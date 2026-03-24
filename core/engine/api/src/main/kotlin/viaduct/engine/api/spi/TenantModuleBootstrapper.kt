package viaduct.engine.api.spi

import viaduct.engine.api.Coordinate
import viaduct.engine.api.ViaductSchema

/**
 * Interface for the data that a tenant API provides to the engine to bootstrap
 * the tenant's execution environment.
 */
interface TenantModuleBootstrapper {
    /**
     * Will be called by the engine once when the tenant module is bootstrapped,
     * and the resulting iterator will be used just once.  This iterator is
     * thread-safe to support parallel loading.
     *
     * @param schema The full schema, part of which the tenant module
     * is responsible for.
     *
     * @return The elements of map from field coordinates (field-name pair)
     * to the ResolverExecutor for that field.
     *
     * @throws TenantModuleException to indicate an error in loading a
     * module that should terminate the attempt to load this module
     * but that isn't fatal in the sense that it should necessarily
     * terminate the loading of other modules.
     */
    fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Coordinate, FieldResolverExecutor>>

    /**
     * Will be called by the engine once when the tenant module is bootstrapped,
     * and the resulting iterator will be used just once.  This iterator is
     * thread-safe to support parallel loading.
     *
     * @return For node resolvers that implement either the batching or non-batching resolve function,
     * returns the elements of map from node name to the [NodeResolverExecutor] for that node resolver.
     *
     * @throws TenantModuleException to indicate an error in loading a
     * module that should terminate the attempt to load this module
     * but that isn't fatal in the sense that it should necessarily
     * terminate the loading of other modules.
     */
    fun nodeResolverExecutors(schema: ViaductSchema): Iterable<Pair<String, NodeResolverExecutor>>
}

/**
 * Thrown by member of [TenantModuleBootstrapper] to indicate an error in loading a
 * module that should terminate the attempt to load this module but that isn't
 * fatal in the sense that it should necessarily terminate the loading of other modules.
 */
class TenantModuleException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
