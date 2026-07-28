package viaduct.tenant.codegen.kotlingen

import java.io.File
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.isBatchingResolver
import viaduct.tenant.codegen.bytecode.config.isNode
import viaduct.tenant.codegen.bytecode.config.isSelectiveResolver
import viaduct.tenant.codegen.bytecode.config.tenantModule

private const val RESOLVER_DIRECTIVE = "resolver"

fun ViaductSchema.generateNodeResolvers(args: Args) {
    val gen = NodeResolverGenerator(
        this,
        args.tenantPackage,
        args.tenantPackagePrefix,
        args.tenantSchemaModulePrefix,
        args.grtPackage,
        args.resolverGeneratedDir,
        args.isFeatureAppTest
    )
    gen.generate()
}

private class NodeResolverGenerator(
    private val schema: ViaductSchema,
    private val tenantPackage: String,
    private val tenantPackagePrefix: String,
    private val tenantSchemaModulePrefix: String?,
    private val grtPackage: String,
    private val resolverGeneratedDir: File,
    private val isFeatureAppTest: Boolean
) {
    private val targetTenantModule = if (tenantPackage.startsWith("viaduct.testapps.")) {
        // For testapps, preserve the full path
        tenantPackage.replace(".", "/")
    } else {
        // For regular tenants, use the prefix-based approach (like FieldResolverGenerator)
        tenantPackage.replace("$tenantPackagePrefix.", "").replace(".", "/")
    }

    fun generate() {
        val tenantOwnedNodes = schema.types.values
            .filter {
                isTenantOwnedNode(it) && hasResolverDirective(it)
            }
            .map { NodeResolverConfig(it.name, it.isSelectiveResolver, it.isBatchingResolver) }

        genNodeResolvers(tenantOwnedNodes, tenantPackage, grtPackage)?.let { contents ->
            val resolverbasesDir = File(resolverGeneratedDir, "resolverbases")
            resolverbasesDir.mkdirs()
            val file = File(resolverbasesDir, "NodeResolvers.kt")
            contents.write(file)
        }
    }

    private fun isTenantOwnedNode(def: ViaductSchema.TypeDef): Boolean {
        return if (!isFeatureAppTest) {
            def.isNode && tenantOwnsModule(def.sourceLocation?.tenantModule, targetTenantModule, tenantSchemaModulePrefix)
        } else {
            def.isNode
        }
    }

    private fun hasResolverDirective(def: ViaductSchema.TypeDef): Boolean = def.hasAppliedDirective(RESOLVER_DIRECTIVE)
}

internal data class NodeResolverConfig(
    val typeName: String,
    val isSelective: Boolean,
    val isBatching: Boolean,
)

internal fun genNodeResolvers(
    types: List<NodeResolverConfig>,
    tenantPackage: String,
    grtPackage: String
): STContents? =
    if (types.isEmpty()) {
        null
    } else {
        STContents(stGroup, NodesModelImpl(tenantPackage, grtPackage, types))
    }

private interface NodesModel {
    val tenantPackage: String
    val nodes: List<NodeModel>
}

private interface NodeModel {
    val grtPackage: String
    val typeName: String
    val selective: Boolean
    val selectiveLiteral: String
    val batching: Boolean
    val batchingLiteral: String
    val ctxInterface: String
}

private class NodesModelImpl(override val tenantPackage: String, grtPackage: String, typeNames: List<NodeResolverConfig>) : NodesModel {
    override val nodes: List<NodeModel> = typeNames.map { NodeModelImpl(it, grtPackage) }
}

private class NodeModelImpl(config: NodeResolverConfig, override val grtPackage: String) : NodeModel {
    override val typeName: String = config.typeName
    override val selective: Boolean = config.isSelective
    override val selectiveLiteral: String = selective.toString()
    override val batching: Boolean = config.isBatching
    override val batchingLiteral: String = batching.toString()
    override val ctxInterface: String = if (selective) {
        "viaduct.api.context.SelectiveNodeExecutionContext"
    } else {
        "viaduct.api.context.NodeExecutionContext"
    }
}

private val nodesSt = stTemplate(
    """
        package <mdl.tenantPackage>.resolverbases

        import viaduct.api.FieldValue
        import viaduct.apiannotations.ExperimentalApi
        import viaduct.apiannotations.InternalApi
        import viaduct.api.internal.InternalContext
        import viaduct.api.NodeResolverBase
        import viaduct.api.internal.NodeResolverFor
        import viaduct.api.select.SelectionSet

        @OptIn(InternalApi::class, ExperimentalApi::class)
        object NodeResolvers {
            <mdl.nodes:node(); separator="\n">
        }
    """.trimIndent()
)

private val nodeSt = stTemplate(
    "node(mdl)",
    """
        @NodeResolverFor(typeName = "<mdl.typeName>", isSelective = <mdl.selectiveLiteral>, isBatching = <mdl.batchingLiteral>)
        abstract class <mdl.typeName> : viaduct.api.ResolverBase\<<mdl.grtPackage>.<mdl.typeName>\>, NodeResolverBase\<<mdl.grtPackage>.<mdl.typeName>\><if(mdl.batching)>, viaduct.api.internal.BaseBatchedNodeResolver<else>, viaduct.api.internal.BaseUnbatchedNodeResolver<endif> {
            <if(!mdl.batching)>
            abstract suspend fun resolve(ctx: Context): <mdl.grtPackage>.<mdl.typeName>

            @Suppress("UNCHECKED_CAST")
            final override suspend fun invokeNodeResolver(
                context: viaduct.api.context.NodeExecutionContext\<*>
            ): Any? = resolve(
                Context(context as <mdl.ctxInterface>\<<mdl.grtPackage>.<mdl.typeName>\>)
            )
            <endif>
            <if(mdl.batching)>
            abstract suspend fun batchResolve(contexts: List\<Context>): List\<FieldValue\<<mdl.grtPackage>.<mdl.typeName>\>>

            @Suppress("UNCHECKED_CAST")
            final override suspend fun invokeNodeBatchResolver(
                contexts: List\<viaduct.api.context.NodeExecutionContext\<*\>>
            ): Any? = batchResolve(
                contexts.map { Context(it as <mdl.ctxInterface>\<<mdl.grtPackage>.<mdl.typeName>\>) }
            )
            <endif>

            class Context(
                private val inner: <mdl.ctxInterface>\<<mdl.grtPackage>.<mdl.typeName>\>
            ) : <mdl.ctxInterface>\<<mdl.grtPackage>.<mdl.typeName>\> by inner, InternalContext by (inner as InternalContext) {
                <if(mdl.selective)>
                override fun selections(): SelectionSet\<<mdl.grtPackage>.<mdl.typeName>\> = inner.selections()
                <endif>
            }
        }
    """.trimIndent()
)

private val stGroup = nodesSt + nodeSt
