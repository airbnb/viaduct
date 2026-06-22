package viaduct.service.api.spi

import viaduct.apiannotations.StableApi

/**
 * Marks a class as the bootstrapper for a Viaduct tenant module.
 *
 * Exactly one class per tenant module should carry this annotation. The framework's KSP processor
 * detects it at build time and writes the fully-qualified class name into the tenant's config file
 * under `META-INF/viaduct/modules/`. At startup, [TenantModuleInjectorFactory.bootstrap] receives
 * this class and uses it to create a per-tenant [CodeInjector].
 *
 * The annotated class must implement the type required by the service engineer's
 * [TenantModuleInjectorFactory] implementation
 */
@StableApi
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class TenantBootstrapper
