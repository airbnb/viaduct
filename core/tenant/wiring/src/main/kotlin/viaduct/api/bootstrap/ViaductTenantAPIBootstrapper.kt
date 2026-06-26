package viaduct.api.bootstrap

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import viaduct.api.internal.GRTConvFactory
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.api.spi.TenantAPIBootstrapperBuilder
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.InputStreamSource
import viaduct.service.api.spi.TenantModuleInjectorFactory
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
        private val codeInjector: CodeInjector,
        private val tenantPackageFinder: TenantPackageFinder,
        private val tenantResolverClassFinderFactory: TenantResolverClassFinderFactory,
        private val grtConvFactory: GRTConvFactory,
        private val executorRegistryConfigSources: List<InputStreamSource> = emptyList(),
        private val tenantModuleInjectorFactory: TenantModuleInjectorFactory? = null,
    ) : TenantAPIBootstrapper {
        /**
         * Discovers all Viaduct TenantModule(s) and creates ViaductTenantModuleBootstrapper for each tenant.
         *
         * @return List of all TenantModuleBootstrapper(s), one for each Viaduct TenantModule.
         */
        @Suppress("DEPRECATION")
        override suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper> {
            log.info("Viaduct Modern Tenant API Bootstrapper: Creating bootstrappers for tenant modules")
            val tenantPackageInfos = tenantPackageFinder.tenantPackages()
            val tenantCodeInjectorsByPackageName = bootstrapTenantCodeInjectorsByPackageName(tenantPackageInfos)

            // Create bootstrappers in parallel.
            return coroutineScope {
                tenantPackageInfos.map { packageInfo ->
                    async {
                        log.info("Creating bootstrapper for tenant module: {}", packageInfo.packageName)
                        ViaductTenantModuleBootstrapper(
                            tenantCodeInjectorsByPackageName.getValue(packageInfo.packageName),
                            createResolverClassFinder(packageInfo),
                            grtConvFactory,
                        )
                    }
                }.awaitAll()
            }
        }

        private suspend fun bootstrapTenantCodeInjectorsByPackageName(tenantPackageInfos: Iterable<TenantPackageInfo>): Map<String, CodeInjector> {
            if (executorRegistryConfigSources.isEmpty()) {
                return tenantPackageInfos.associate { packageInfo -> packageInfo.packageName to codeInjector }
            }

            val injectorFactory = tenantModuleInjectorFactory
                ?: error("tenantModuleInjectorFactory is required when executor registry config sources are provided")
            val registryConfigsByTenantName = registryConfigsByTenantName()

            val tenantCodeInjectorsByPackageName = tenantPackageInfos.associate { packageInfo ->
                val tenantName = tenantRegistryName(packageInfo)
                val registryConfig = registryConfigsByTenantName[tenantName]
                    ?: error(
                        "Missing execution registry config for scanner-discovered tenant ${packageInfo.packageName} (tenantName=$tenantName)"
                    )
                val bootstrapClassName = registryConfig.bootstrapClass?.takeIf(String::isNotBlank)
                    ?: error("Missing bootstrapClass in execution registry config for tenantName=$tenantName")

                packageInfo.packageName to injectorFactory.bootstrap(
                    tenantName = tenantName,
                    tenantBootstrapClass = Class.forName(bootstrapClassName),
                )
            }

            if (tenantCodeInjectorsByPackageName.isNotEmpty()) {
                injectorFactory.finalize()
            }

            return tenantCodeInjectorsByPackageName
        }

        private fun registryConfigsByTenantName(): Map<String, ExecutionRegistryConfigFile> =
            executorRegistryConfigSources
                .map { source ->
                    source.openStream().use { inputStream ->
                        objectMapper.readValue<ExecutionRegistryConfigFile>(inputStream)
                    }
                }
                .mapNotNull { registry ->
                    registry.tenantName
                        ?.takeIf(String::isNotBlank)
                        ?.let { tenantName -> tenantName to registry }
                }
                .toMap()

        private fun tenantRegistryName(packageInfo: TenantPackageInfo): String =
            packageInfo.packageName
                .removePrefix(AIRBNB_TENANT_PACKAGE_PREFIX)
                .removePrefix(".")
                .replace('.', '/')

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
        open class Builder : TenantAPIBootstrapperBuilder {
            protected var codeInjector: CodeInjector = CodeInjector.Naive
            protected var tenantPackagePrefix: String? = null
            protected var tenantPackageFinder: TenantPackageFinder? = null
            protected var tenantResolverClassFinderFactory: TenantResolverClassFinderFactory? = null
            protected var grtConvFactory: GRTConvFactory = CachingGRTConvFactory()
            protected var executorRegistryConfigSources: List<InputStreamSource> = emptyList()
            protected var tenantModuleInjectorFactory: TenantModuleInjectorFactory? = null

            fun tenantCodeInjector(tenantCodeInjector: CodeInjector) =
                apply {
                    this.codeInjector = tenantCodeInjector
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

            fun grtConvFactory(grtConvFactory: GRTConvFactory) =
                apply {
                    this.grtConvFactory = grtConvFactory
                }

            fun executorRegistryConfigSources(executorRegistryConfigSources: List<InputStreamSource>) =
                apply {
                    this.executorRegistryConfigSources = executorRegistryConfigSources
                }

            fun tenantModuleInjectorFactory(tenantModuleInjectorFactory: TenantModuleInjectorFactory) =
                apply {
                    this.tenantModuleInjectorFactory = tenantModuleInjectorFactory
                }

            protected fun resolvedTenantPackageFinder(): TenantPackageFinder =
                when {
                    tenantPackagePrefix != null -> TenantPackageFinder { setOf(TenantPackageInfo(tenantPackagePrefix!!)) }
                    tenantPackageFinder != null -> tenantPackageFinder!!
                    else -> ViaductTenantPackageFinder()
                }

            protected fun resolvedTenantResolverClassFinderFactory(): TenantResolverClassFinderFactory = tenantResolverClassFinderFactory ?: ViaductTenantResolverClassFinderFactory()

            override fun create(): TenantAPIBootstrapper =
                ViaductTenantAPIBootstrapper(
                    codeInjector = codeInjector,
                    tenantPackageFinder = resolvedTenantPackageFinder(),
                    tenantResolverClassFinderFactory = resolvedTenantResolverClassFinderFactory(),
                    grtConvFactory = grtConvFactory,
                    executorRegistryConfigSources = executorRegistryConfigSources,
                    tenantModuleInjectorFactory = tenantModuleInjectorFactory,
                )
        }

        companion object {
            private const val AIRBNB_TENANT_PACKAGE_PREFIX = "com.airbnb.viaduct"
            private val log by logger()
            private val objectMapper = jacksonObjectMapper()
        }
    }
