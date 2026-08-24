package viaduct.tenant.runtime.execution.noderesolver

import com.google.inject.AbstractModule
import com.google.inject.Guice
import javax.inject.Singleton
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.KOTLIN_API_NAME
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.service.api.spi.InputStreamSource
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory
import viaduct.service.api.spi.TenantModuleInjectorFactory
import viaduct.tenant.runtime.bootstrap.GuiceCodeInjector

class FileBasedNodeResolverContractTest : NodeResolverContractTest() {
    override val validateResolverCompleteness = false

    private val injector = Guice.createInjector(
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

    override fun tenantModuleInjectorFactory(): TenantModuleInjectorFactory = SharedTenantModuleInjectorFactory(GuiceCodeInjector(injector))

    override fun grtPackagePrefix(): String = GRT_PACKAGE

    override fun moduleConfigSources(): List<ModuleConfigSource> {
        // Reuse KotlinNodeResolverContractTest's resolver impls — no new classes needed.
        // This simulates what KSP will emit: FQNs pointing at the real resolver implementations.
        val base = "viaduct.tenant.runtime.execution.noderesolver.KotlinNodeResolverContractTest"
        val resolverBases = "viaduct.tenant.runtime.execution.noderesolver.resolverbases"
        val registry = ExecutionRegistryConfigFile(
            version = "1",
            tenantName = "viaduct/tenant/runtime/execution/noderesolver",
            apiName = KOTLIN_API_NAME,
            executorFactory = "viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory",
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
        return listOf(
            ModuleConfigSource.from(
                InputStreamSource.fromString(
                    ExecutionRegistryConfigFile.toJson(registry),
                    name = "noderesolver.json",
                ),
            ),
        )
    }

    private companion object {
        private const val GRT_PACKAGE = "viaduct.tenant.runtime.execution.noderesolver"
        private const val RESOLVER_BASE = "viaduct.tenant.runtime.execution.noderesolver.KotlinNodeResolverContractTest"

        @Suppress("UNCHECKED_CAST")
        private fun resolverClass(name: String): Class<Any> = Class.forName("$RESOLVER_BASE\$$name") as Class<Any>
    }
}
