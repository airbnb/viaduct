package viaduct.tenant.runtime.bootstrap

import com.google.common.annotations.VisibleForTesting
import viaduct.engine.api.TenantModuleMetadata
import viaduct.utils.slf4j.logger

/**
 * Factory for creating ViaductTenantResolverClassFinder instances.
 *
 * This factory implements the TenantResolverClassFinderFactory interface to provide
 * a standardized way of creating tenant resolver class finders configured with
 * specific package names for class discovery.
 *
 * @param grtPackagePrefix The package prefix for GraphQL Representational Types (GRTs)
 *
 * @see ViaductTenantResolverClassFinder
 * @see TenantResolverClassFinderFactory
 */
class ViaductTenantResolverClassFinderFactory
    @VisibleForTesting
    constructor(
        private val grtPackagePrefix: String,
    ) : TenantResolverClassFinderFactory {
        constructor() : this(grtPackagePrefix = "viaduct.api.grts")

        companion object {
            private val log by logger()
        }

        override fun create(packageInfo: TenantPackageInfo): TenantResolverClassFinder = create(packageInfo.packageName, packageInfo.metadata, withNewScanner = false)

        fun create(
            packageName: String,
            metadata: TenantModuleMetadata = TenantModuleMetadata.EMPTY,
            withNewScanner: Boolean = false,
        ): TenantResolverClassFinder {
            return ViaductTenantResolverClassFinder(packageName, grtPackagePrefix, withNewScanner, metadata)
        }
    }
