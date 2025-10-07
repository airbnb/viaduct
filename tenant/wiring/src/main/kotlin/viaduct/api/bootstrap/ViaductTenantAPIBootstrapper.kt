package viaduct.api.bootstrap

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import viaduct.engine.api.TenantAPIBootstrapper
import viaduct.engine.api.TenantModuleBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.api.spi.TenantCodeInjector
import viaduct.tenant.runtime.bootstrap.TenantPackageFinder
import viaduct.tenant.runtime.bootstrap.TenantResolverClassFinderFactory
import viaduct.tenant.runtime.bootstrap.ViaductTenantModuleBootstrapper
import viaduct.tenant.runtime.bootstrap.ViaductTenantPackageFinder
import viaduct.tenant.runtime.bootstrap.ViaductTenantResolverClassFinderFactory
import viaduct.utils.slf4j.logger

/**
 * ViaductTenantAPIBootstrapper is responsible for discovering all Viaduct tenant modules and creating
 * TenantModuleBootstrapper(s), one for each Viaduct TenantModule.
 */
class ViaductTenantAPIBootstrapper
    private constructor(
        private val tenantCodeInjector: TenantCodeInjector,
        private val tenantPackageFinder: TenantPackageFinder,
        private val tenantResolverClassFinderFactory: TenantResolverClassFinderFactory,
    ) : TenantAPIBootstrapper {
    /*
     * Discovers all Viaduct TenantModule(s) and creates ViaductTenantModuleBootstrapper for each tenant.
     *
     * @return List of all TenantModuleBootstrapper(s), one for each Viaduct TenantModule.
     */
        override suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper> {
            log.info("Viaduct Modern Tenant API Bootstrapper: Creating bootstrappers for tenant modules")
            val tenantModuleNames = tenantPackageFinder.tenantPackages()

            // Create bootstrappers in parallel.
            return coroutineScope {
                tenantModuleNames.map { tenantModuleName ->
                    async {
                        log.info("Creating bootstrapper for tenant module: $tenantModuleName")
                        ViaductTenantModuleBootstrapper(
                            tenantCodeInjector,
                            tenantResolverClassFinderFactory.create(tenantModuleName),
                        )
                    }
                }.awaitAll()
            }
        }

        /**
         * Builder for creating a ViaductTenantAPIBootstrapper instance.
         */
        class Builder : TenantAPIBootstrapperBuilder {
            private var tenantCodeInjector: TenantCodeInjector = TenantCodeInjector.Naive
            private var tenantPackagePrefix: String? = null
            private var tenantPackageFinder: TenantPackageFinder? = null
            private var tenantResolverClassFinderFactory: TenantResolverClassFinderFactory? = null

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

            fun tenantResolverClassFinderFactory(tenantResolverClassFinderFactory: TenantResolverClassFinderFactory) =
                apply {
                    this.tenantResolverClassFinderFactory = tenantResolverClassFinderFactory
                }

            override fun create(): ViaductTenantAPIBootstrapper {
                val tenantPackageFinder = when {
                    tenantPackagePrefix != null -> TenantPackageFinder { setOf(tenantPackagePrefix!!) }
                    tenantPackageFinder != null -> tenantPackageFinder!!
                    else -> ViaductTenantPackageFinder()
                }

                val finalTenantResolverClassFinderFactory =
                    tenantResolverClassFinderFactory ?: ViaductTenantResolverClassFinderFactory()

                return ViaductTenantAPIBootstrapper(
                    tenantCodeInjector = tenantCodeInjector,
                    tenantPackageFinder = tenantPackageFinder,
                    tenantResolverClassFinderFactory = finalTenantResolverClassFinderFactory
                )
            }
        }

        companion object {
            private val log by logger()
        }
    }
