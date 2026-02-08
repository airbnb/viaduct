package viaduct.tenant.runtime.bootstrap

import viaduct.api.TenantModule
import viaduct.utils.classgraph.ClassGraphScanner
import viaduct.utils.slf4j.logger

/**
 * An implementation of the TenantPackageFinder interface that uses
 * [ClassGraphScanner.INSTANCE] to find tenant modules.
 *
 * This reuses the shared class graph scan result instead of performing
 * a separate scan, improving startup time.
 *
 * Results are filtered to only include modules from the [TENANT_PACKAGE_PREFIX]
 * to maintain backward compatibility with the original behavior.
 */
class ViaductTenantPackageFinder : TenantPackageFinder {
    override fun tenantPackages(): Set<String> {
        val tenantInterfaceClass = TenantModule::class.java
        val tenantModuleClasses =
            ClassGraphScanner.INSTANCE
                .getSubTypesOf(tenantInterfaceClass, packagesFilter = setOf(TENANT_PACKAGE_PREFIX))
        return tenantModuleClasses.map { it.packageName }.toSet()
    }

    companion object {
        private val log by logger()

        // TODO: do not expose airbnb internals to OSS repo.
        private const val TENANT_PACKAGE_PREFIX = "com.airbnb.viaduct"
    }
}
