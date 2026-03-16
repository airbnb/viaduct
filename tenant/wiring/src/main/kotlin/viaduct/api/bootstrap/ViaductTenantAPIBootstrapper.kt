package viaduct.api.bootstrap

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import viaduct.api.internal.GRTConvFactory
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.api.spi.TenantCodeInjector
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.bootstrap.TenantPackageFinder
import viaduct.tenant.runtime.bootstrap.TenantPackageInfo
import viaduct.tenant.runtime.bootstrap.TenantResolverClassFinder
import viaduct.tenant.runtime.bootstrap.TenantResolverClassFinderFactory
import viaduct.tenant.runtime.bootstrap.ViaductTenantModuleBootstrapper
import viaduct.tenant.runtime.bootstrap.ViaductTenantPackageFinder
import viaduct.tenant.runtime.bootstrap.ViaductTenantResolverClassFinderFactory
import viaduct.tenant.runtime.internal.CachingGRTConvFactory
import viaduct.utils.slf4j.logger

/**
 * ViaductTenantAPIBootstrapper is responsible for discovering all Viaduct tenant modules and creating
 * TenantModuleBootstrapper(s), one for each Viaduct TenantModule.
 *
 * Subclasses can override [createResolverClassFinder] to control how the class finder is created
 * for each tenant package (e.g., to support hotswap scenarios with a fresh scanner).
 */
open class ViaductTenantAPIBootstrapper
    protected constructor(
        private val tenantCodeInjector: TenantCodeInjector,
        private val tenantPackageFinder: TenantPackageFinder,
        private val tenantResolverClassFinderFactory: TenantResolverClassFinderFactory,
        private val globalIDCodec: GlobalIDCodec,
        private val grtConvFactory: GRTConvFactory,
    ) : TenantAPIBootstrapper {
        /**
         * Discovers all Viaduct TenantModule(s) and creates ViaductTenantModuleBootstrapper for each tenant.
         *
         * @return List of all TenantModuleBootstrapper(s), one for each Viaduct TenantModule.
         */
        override suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper> {
            log.info("Viaduct Modern Tenant API Bootstrapper: Creating bootstrappers for tenant modules")
            val tenantPackageInfos = tenantPackageFinder.tenantPackages()

            // Create bootstrappers in parallel.
            return coroutineScope {
                tenantPackageInfos.map { packageInfo ->
                    async {
                        log.info("Creating bootstrapper for tenant module: {}", packageInfo.packageName)
                        ViaductTenantModuleBootstrapper(
                            tenantCodeInjector,
                            createResolverClassFinder(packageInfo),
                            globalIDCodec,
                            grtConvFactory,
                        )
                    }
                }.awaitAll()
            }
        }

        /**
         * Creates a [TenantResolverClassFinder] for the given package name.
         *
         * Subclasses can override this method to provide custom scanner behavior,
         * for example to create a fresh scanner when hotswapping classes.
         *
         * @param packageName the tenant package name to scan
         * @return a configured [TenantResolverClassFinder] for the package
         */
        @Deprecated("Experimental, for Airbnb use only", level = DeprecationLevel.WARNING)
        protected open fun createResolverClassFinder(packageInfo: TenantPackageInfo): TenantResolverClassFinder = tenantResolverClassFinderFactory.create(packageInfo)

        /**
         * Builder for creating a ViaductTenantAPIBootstrapper instance.
         */
        open class Builder : TenantAPIBootstrapperBuilder<TenantModuleBootstrapper> {
            protected var tenantCodeInjector: TenantCodeInjector = TenantCodeInjector.Naive
            protected var tenantPackagePrefix: String? = null
            protected var tenantPackageFinder: TenantPackageFinder? = null
            protected var tenantResolverClassFinderFactory: TenantResolverClassFinderFactory? = null
            protected var globalIDCodec: GlobalIDCodec = GlobalIDCodecDefault
            protected var grtConvFactory: GRTConvFactory = CachingGRTConvFactory()

            fun tenantCodeInjector(tenantCodeInjector: TenantCodeInjector) =
                apply {
                    this.tenantCodeInjector = tenantCodeInjector
                }

            fun tenantPackagePrefix(tenantPackagePrefix: String) =
                apply {
                    this.tenantPackagePrefix = tenantPackagePrefix
                }

            @Deprecated("For advance test uses, Airbnb only use.", level = DeprecationLevel.WARNING)
            fun tenantPackageFinder(tenantPackageFinder: TenantPackageFinder) =
                apply {
                    this.tenantPackageFinder = tenantPackageFinder
                }

            @Deprecated("For advance test uses, Airbnb only use.", level = DeprecationLevel.WARNING)
            fun tenantResolverClassFinderFactory(tenantResolverClassFinderFactory: TenantResolverClassFinderFactory) =
                apply {
                    this.tenantResolverClassFinderFactory = tenantResolverClassFinderFactory
                }

            /**
             * Configures the GlobalIDCodec for serializing and deserializing GlobalIDs.
             * All tenant modules bootstrapped by this instance will share this codec.
             *
             * @param globalIDCodec The GlobalIDCodec instance to use
             * @return This Builder instance for method chaining
             */
            fun globalIDCodec(globalIDCodec: GlobalIDCodec) =
                apply {
                    this.globalIDCodec = globalIDCodec
                }

            fun grtConvFactory(grtConvFactory: GRTConvFactory) =
                apply {
                    this.grtConvFactory = grtConvFactory
                }

            protected fun resolvedTenantPackageFinder(): TenantPackageFinder =
                when {
                    tenantPackagePrefix != null -> TenantPackageFinder { setOf(TenantPackageInfo(tenantPackagePrefix!!)) }
                    tenantPackageFinder != null -> tenantPackageFinder!!
                    else -> ViaductTenantPackageFinder()
                }

            protected fun resolvedTenantResolverClassFinderFactory(): TenantResolverClassFinderFactory = tenantResolverClassFinderFactory ?: ViaductTenantResolverClassFinderFactory()

            override fun create(): ViaductTenantAPIBootstrapper =
                ViaductTenantAPIBootstrapper(
                    tenantCodeInjector = tenantCodeInjector,
                    tenantPackageFinder = resolvedTenantPackageFinder(),
                    tenantResolverClassFinderFactory = resolvedTenantResolverClassFinderFactory(),
                    globalIDCodec = globalIDCodec,
                    grtConvFactory = grtConvFactory,
                )
        }

        companion object {
            private val log by logger()
        }
    }
