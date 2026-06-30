package viaduct.engine.api.spi

/**
 * Provides the tenant module bootstrappers for one flavor of the Viaduct Tenant API.
 */
interface TenantAPIBootstrapper {
    /**
     * Provides the tenant module bootstrappers used to contribute resolver executors.
     *
     * The engine calls this once per Tenant API during startup and iterates over the returned
     * iterable once.
     */
    suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper>
}

/**
 * Builder for [TenantAPIBootstrapper] implementations.
 */
interface TenantAPIBootstrapperBuilder {
    /** Creates a new [TenantAPIBootstrapper] instance. */
    fun create(): TenantAPIBootstrapper
}

/** Flattens an iterable of [TenantAPIBootstrapper] into a single instance. */
fun Iterable<TenantAPIBootstrapper>.flatten(): TenantAPIBootstrapper = FlattenTenantAPIBootstrapper(this)

private class FlattenTenantAPIBootstrapper(
    private val items: Iterable<TenantAPIBootstrapper>,
) : TenantAPIBootstrapper {
    override suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper> = items.flatMap { it.tenantModuleBootstrappers() }
}
