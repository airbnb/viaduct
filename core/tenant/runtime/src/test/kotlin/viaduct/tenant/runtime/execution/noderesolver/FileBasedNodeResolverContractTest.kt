package viaduct.tenant.runtime.execution.noderesolver

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Singleton
import viaduct.api.internal.DefaultGRTConvFactory
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistry
import viaduct.engine.api.bootstrap.executionregistry.FieldAPIData
import viaduct.engine.api.bootstrap.executionregistry.FieldEntry
import viaduct.engine.api.bootstrap.executionregistry.NodeAPIData
import viaduct.engine.api.bootstrap.executionregistry.NodeEntry
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapper as BaseTenantAPIBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.tenant.runtime.bootstrap.ExecutionRegistryBootstrapper
import viaduct.tenant.runtime.bootstrap.GuiceTenantCodeInjector

class FileBasedNodeResolverContractTest : NodeResolverContractTest() {
    override val validateResolverCompleteness = false

    override fun createBootstrapperBuilder(): TenantAPIBootstrapperBuilder<TenantModuleBootstrapper> {
        // Reuse KotlinNodeResolverContractTest's resolver impls — no new classes needed.
        // This simulates what KSP will emit: FQNs pointing at the real resolver implementations.
        val base = "viaduct.tenant.runtime.execution.noderesolver.KotlinNodeResolverContractTest"
        val resolverBases = "viaduct.tenant.runtime.execution.noderesolver.resolverbases"

        val registry = ExecutionRegistry(
            version = "1",
            executorFactory = "viaduct.api.internal.DefaultGRTConvFactory",
            nodes = listOf(
                NodeEntry(
                    typeName = "NodeObj",
                    isBatching = false,
                    isSelective = false,
                    attribution = "NodeObj",
                    tenantAPIData = NodeAPIData(
                        resolverClass = "$base\$NodeObjResolver",
                        resolverBaseClass = "$resolverBases.NodeResolvers\$NodeObj",
                    ),
                ),
            ),
            fields = listOf(
                FieldEntry(
                    typeName = "Query",
                    fieldName = "nodeObj",
                    isBatching = false,
                    isSelective = false,
                    attribution = "Query.nodeObj",
                    tenantAPIData = FieldAPIData(
                        resolverClass = "$base\$QueryNodeObjResolver",
                        resolverBaseClass = "$resolverBases.QueryResolvers\$NodeObj",
                        queryTypeName = "Query",
                    ),
                ),
                FieldEntry(
                    typeName = "Query",
                    fieldName = "nodeReference",
                    isBatching = false,
                    isSelective = false,
                    attribution = "Query.nodeReference",
                    tenantAPIData = FieldAPIData(
                        resolverClass = "$base\$NodeReferenceResolver",
                        resolverBaseClass = "$resolverBases.QueryResolvers\$NodeReference",
                        queryTypeName = "Query",
                    ),
                ),
                FieldEntry(
                    typeName = "Query",
                    fieldName = "objectWithNodeField",
                    isBatching = false,
                    isSelective = false,
                    attribution = "Query.objectWithNodeField",
                    tenantAPIData = FieldAPIData(
                        resolverClass = "$base\$ObjectWithNodeFieldResolver",
                        resolverBaseClass = "$resolverBases.QueryResolvers\$ObjectWithNodeField",
                        queryTypeName = "Query",
                    ),
                ),
            ),
        )

        val resolverClass = { name: String ->
            @Suppress("UNCHECKED_CAST")
            Class.forName("$base\$$name") as Class<Any>
        }

        val injector = Guice.createInjector(
            object : AbstractModule() {
                override fun configure() {
                    bind(resolverClass("QueryNodeObjResolver")).`in`(Singleton::class.java)
                    bind(resolverClass("NodeReferenceResolver")).`in`(Singleton::class.java)
                    bind(resolverClass("ObjectWithNodeFieldResolver")).`in`(Singleton::class.java)
                    bind(resolverClass("NodeObjResolver")).`in`(Singleton::class.java)
                }
            }
        )

        val bootstrapper = ExecutionRegistryBootstrapper(
            registry = registry,
            tenantCodeInjector = GuiceTenantCodeInjector(injector),
            grtPackagePrefix = "viaduct.tenant.runtime.execution.noderesolver",
            grtConvFactory = DefaultGRTConvFactory,
        )

        return object : TenantAPIBootstrapperBuilder<TenantModuleBootstrapper> {
            override fun create() =
                object : BaseTenantAPIBootstrapper<TenantModuleBootstrapper> {
                    override suspend fun tenantModuleBootstrappers() = listOf(bootstrapper)
                }
        }
    }
}
