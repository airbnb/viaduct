@file:Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION") // for imports of legacy bootstrap shim

package viaduct.tenant.runtime.execution.noderesolver

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Singleton
import java.net.URI
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldAPIData
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeAPIData
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper
import viaduct.engine.runtime.tenantloading.ExecutionRegistryTenantModuleBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapper as BaseTenantAPIBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.tenant.runtime.bootstrap.GuiceCodeInjector
import viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory

class FileBasedNodeResolverContractTest : NodeResolverContractTest() {
    override val validateResolverCompleteness = false

    override fun createBootstrapperBuilder(): TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper> {
        // Reuse KotlinNodeResolverContractTest's resolver impls — no new classes needed.
        // This simulates what KSP will emit: FQNs pointing at the real resolver implementations.
        val base = "viaduct.tenant.runtime.execution.noderesolver.KotlinNodeResolverContractTest"
        val resolverBases = "viaduct.tenant.runtime.execution.noderesolver.resolverbases"

        val registry = ExecutionRegistryConfigFile(
            version = "1",
            executorFactory = "viaduct.api.internal.DefaultGRTConvFactory",
            grtPackagePrefix = "viaduct.tenant.runtime.execution.noderesolver",
            nodes = listOf(
                NodeEntryConfig(
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
                FieldEntryConfig(
                    typeName = "Query",
                    fieldName = "nodeObj",
                    isBatching = false,
                    isSelective = false,
                    attribution = "Query.nodeObj",
                    tenantAPIData = FieldAPIData(
                        resolverClass = "$base\$QueryNodeObjResolver",
                        resolverBaseClass = "$resolverBases.QueryResolvers\$NodeObj",
                        queryTypeName = "Query",
                        hasArguments = true,
                        returnTypeName = "NodeObj",
                    ),
                ),
                FieldEntryConfig(
                    typeName = "Query",
                    fieldName = "nodeReference",
                    isBatching = false,
                    isSelective = false,
                    attribution = "Query.nodeReference",
                    tenantAPIData = FieldAPIData(
                        resolverClass = "$base\$NodeReferenceResolver",
                        resolverBaseClass = "$resolverBases.QueryResolvers\$NodeReference",
                        queryTypeName = "Query",
                        hasArguments = true,
                        returnTypeName = "NodeObj",
                    ),
                ),
                FieldEntryConfig(
                    typeName = "Query",
                    fieldName = "objectWithNodeField",
                    isBatching = false,
                    isSelective = false,
                    attribution = "Query.objectWithNodeField",
                    tenantAPIData = FieldAPIData(
                        resolverClass = "$base\$ObjectWithNodeFieldResolver",
                        resolverBaseClass = "$resolverBases.QueryResolvers\$ObjectWithNodeField",
                        queryTypeName = "Query",
                        returnTypeName = "ObjectWithNodeField",
                    ),
                ),
                FieldEntryConfig(
                    typeName = "Query",
                    fieldName = "nodeRefWithIllegalAccess",
                    isBatching = false,
                    isSelective = false,
                    attribution = "Query.nodeRefWithIllegalAccess",
                    tenantAPIData = FieldAPIData(
                        resolverClass = "$base\$NodeRefWithIllegalAccessResolver",
                        resolverBaseClass = "$resolverBases.QueryResolvers\$NodeRefWithIllegalAccess",
                        queryTypeName = "Query",
                        returnTypeName = "NodeObj",
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
                    bind(resolverClass("NodeRefWithIllegalAccessResolver")).`in`(Singleton::class.java)
                }
            }
        )

        val factory = ViaductModernExecutorFactory(
            codeInjector = GuiceCodeInjector(injector),
            grtPackagePrefix = "viaduct.tenant.runtime.execution.noderesolver",
            configUrl = URI("file:///dev/null").toURL(),
        )
        val bootstrapper = ExecutionRegistryTenantModuleBootstrapper(
            registry = registry,
            executorFactory = factory,
        )

        return object : TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper> {
            override fun create() =
                object : BaseTenantAPIBootstrapper<LegacyTenantModuleBootstrapper> {
                    override suspend fun tenantModuleBootstrappers() = listOf(bootstrapper)
                }
        }
    }
}
