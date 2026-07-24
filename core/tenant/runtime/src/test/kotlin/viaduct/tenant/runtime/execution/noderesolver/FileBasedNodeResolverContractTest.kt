package viaduct.tenant.runtime.execution.noderesolver

import com.google.inject.AbstractModule
import com.google.inject.Guice
import javax.inject.Singleton
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.api.spi.TenantAPIBootstrapperBuilder
import viaduct.engine.runtime.tenantloading.ExecutionRegistryTenantModuleBootstrapper
import viaduct.tenant.runtime.bootstrap.GuiceCodeInjector
import viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory

class FileBasedNodeResolverContractTest : NodeResolverContractTest() {
    override val validateResolverCompleteness = false

    override fun createBootstrapperBuilder(): TenantAPIBootstrapperBuilder {
        // Reuse KotlinNodeResolverContractTest's resolver impls — no new classes needed.
        // This simulates what KSP will emit: FQNs pointing at the real resolver implementations.
        val base = "viaduct.tenant.runtime.execution.noderesolver.KotlinNodeResolverContractTest"
        val resolverBases = "viaduct.tenant.runtime.execution.noderesolver.resolverbases"
        val registry = ExecutionRegistryConfigFile(
            version = "1",
            executorFactory = "viaduct.api.internal.DefaultGRTConvFactory",
            nodes = listOf(
                NodeEntryConfig(
                    typeName = "NodeObj",
                    isBatching = false,
                    isSelective = false,
                    attribution = "NodeObj",
                    tenantAPIData = mapOf(
                        "resolverClass" to "$base\$NodeObjResolver",
                        "resolverBaseClass" to "$resolverBases.NodeResolvers\$NodeObj",
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
                    tenantAPIData = mapOf(
                        "resolverClass" to "$base\$QueryNodeObjResolver",
                        "resolverBaseClass" to "$resolverBases.QueryResolvers\$NodeObj",
                        "queryTypeName" to "Query",
                        "hasArguments" to true,
                        "returnTypeName" to "NodeObj",
                    ),
                ),
                FieldEntryConfig(
                    typeName = "Query",
                    fieldName = "nodeReference",
                    isBatching = false,
                    isSelective = false,
                    attribution = "Query.nodeReference",
                    tenantAPIData = mapOf(
                        "resolverClass" to "$base\$NodeReferenceResolver",
                        "resolverBaseClass" to "$resolverBases.QueryResolvers\$NodeReference",
                        "queryTypeName" to "Query",
                        "hasArguments" to true,
                        "returnTypeName" to "NodeObj",
                    ),
                ),
                FieldEntryConfig(
                    typeName = "Query",
                    fieldName = "objectWithNodeField",
                    isBatching = false,
                    isSelective = false,
                    attribution = "Query.objectWithNodeField",
                    tenantAPIData = mapOf(
                        "resolverClass" to "$base\$ObjectWithNodeFieldResolver",
                        "resolverBaseClass" to "$resolverBases.QueryResolvers\$ObjectWithNodeField",
                        "queryTypeName" to "Query",
                        "returnTypeName" to "ObjectWithNodeField",
                    ),
                ),
                FieldEntryConfig(
                    typeName = "Query",
                    fieldName = "nodeRefWithIllegalAccess",
                    isBatching = false,
                    isSelective = false,
                    attribution = "Query.nodeRefWithIllegalAccess",
                    tenantAPIData = mapOf(
                        "resolverClass" to "$base\$NodeRefWithIllegalAccessResolver",
                        "resolverBaseClass" to "$resolverBases.QueryResolvers\$NodeRefWithIllegalAccess",
                        "queryTypeName" to "Query",
                        "returnTypeName" to "NodeObj",
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
            registry = registry,
        )
        val bootstrapper = ExecutionRegistryTenantModuleBootstrapper(
            registry = registry,
            executorFactory = factory,
        )
        return object : TenantAPIBootstrapperBuilder {
            override fun create() =
                object : TenantAPIBootstrapper {
                    override suspend fun tenantModuleBootstrappers() = listOf(bootstrapper)
                }
        }
    }
}
