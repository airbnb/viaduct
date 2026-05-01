package viaduct.java.runtime.bridge

import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapper

/**
 * Java-friendly implementation of [TenantAPIBootstrapper] that wraps a plain [Iterable].
 *
 * Kotlin's `suspend fun` interface cannot be implemented via a Java lambda or anonymous class.
 * Use this class from Java to wrap a list of [TenantModuleBootstrapper] instances:
 *
 * ```java
 * new JavaTenantAPIBootstrapper(List.of(myBootstrapper))
 * ```
 */
class JavaTenantAPIBootstrapper(
    private val bootstrappers: Iterable<TenantModuleBootstrapper>
) : TenantAPIBootstrapper<TenantModuleBootstrapper> {
    override suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper> = bootstrappers
}
