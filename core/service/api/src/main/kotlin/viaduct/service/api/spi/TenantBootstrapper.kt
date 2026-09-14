package viaduct.service.api.spi

import viaduct.apiannotations.StableApi

/**
 * Marks a class as the bootstrapper for a Viaduct tenant module.
 *
 * At most one class per tenant module may carry this annotation. The Kotlin KSP and Java annotation
 * processors detect it at build time and write the class name into the tenant's config file
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
