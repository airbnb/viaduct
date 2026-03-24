package viaduct.tenant.runtime.bootstrap

import viaduct.engine.api.TenantModuleMetadata

/**
 * Holds information about a tenant module package.
 *
 * @param packageName the Java package name identifying the tenant module
 * @param metadata typed metadata from the tenant module
 */
data class TenantPackageInfo(
    val packageName: String,
    val metadata: TenantModuleMetadata = TenantModuleMetadata.EMPTY,
)

fun interface TenantPackageFinder {
    /**
     * Returns a set of all tenant modules to consider in a given context such as an executor registry.
     * Each tenant module is uniquely identified by its Java package name and carries its metadata.
     */
    fun tenantPackages(): Set<TenantPackageInfo>
}
