@file:Suppress("unused", "ClassName")
@file:OptIn(viaduct.apiannotations.VisibleForTest::class)

package viaduct.tenant.runtime.execution.filebased

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Singleton
import viaduct.api.Resolver
import viaduct.engine.BootstrapperFactory
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.tenant.runtime.bootstrap.GuiceTenantCodeInjector
import viaduct.tenant.runtime.execution.filebased.resolverbases.NodeResolvers

class FileBasedBootstrapFeatureAppTest : FileBasedBootstrapContractTest() {
    override val validateResolverCompleteness = false

    @Resolver
    class ItemResolver : NodeResolvers.Item() {
        override suspend fun resolve(ctx: Context): Item {
            return Item.Builder(ctx).id(ctx.id).name(ctx.id.internalID).build()
        }
    }

    override fun createBootstrapperBuilder(): TenantAPIBootstrapperBuilder<TenantModuleBootstrapper> {
        val injector = Guice.createInjector(
            object : AbstractModule() {
                override fun configure() {
                    bind(ItemResolver::class.java).`in`(Singleton::class.java)
                }
            }
        )

        return object : TenantAPIBootstrapperBuilder<TenantModuleBootstrapper> {
            override fun create() =
                BootstrapperFactory.fromResources(
                    tenantCodeInjector = GuiceTenantCodeInjector(injector),
                    packagePrefix = FileBasedBootstrapFeatureAppTest::class.java.packageName,
                )
        }
    }
}
