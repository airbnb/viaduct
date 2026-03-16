package viaduct.tenant.runtime.bootstrap

import viaduct.api.TenantModule
import viaduct.engine.api.TenantModuleMetadata
import viaduct.utils.classgraph.ClassGraphScanner

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
    override fun tenantPackages(): Set<TenantPackageInfo> {
        val tenantInterfaceClass = TenantModule::class.java
        val tenantModuleClasses =
            ClassGraphScanner.INSTANCE
                .getSubTypesOf(tenantInterfaceClass, packagesFilter = setOf(TENANT_PACKAGE_PREFIX))
        return tenantModuleClasses.map { clazz ->
            val module = clazz.getDeclaredConstructor().newInstance()
            TenantPackageInfo(packageName = clazz.packageName, metadata = TenantModuleMetadata.fromMap(module.metadata))
        }.toSet()
    }

    companion object {
        // TODO: do not expose airbnb internals to OSS repo.
        private const val TENANT_PACKAGE_PREFIX = "com.airbnb.viaduct"
    }
}
