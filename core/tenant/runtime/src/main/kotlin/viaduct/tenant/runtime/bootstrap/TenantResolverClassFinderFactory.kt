package viaduct.tenant.runtime.bootstrap

/**
 * Factory interface for creating tenant-specific class finders.
 *
 * This factory provides a standardized mechanism for instantiating TenantResolverClassFinder
 * implementations that can discover and load resolver classes from tenant-specific JAR files.
 * Each factory instance is designed to work with a single tenant's resolver classes identified uniquely
 * by the packageName.
 *
 * @see TenantResolverClassFinder
 */
fun interface TenantResolverClassFinderFactory {
    /**
     * Creates a TenantResolverClassFinder configured to scan the specified tenant package.
     *
     * @param packageInfo the tenant package info including package name and module metadata
     * @return a configured TenantResolverClassFinder instance
     */
    fun create(packageInfo: TenantPackageInfo): TenantResolverClassFinder
}
