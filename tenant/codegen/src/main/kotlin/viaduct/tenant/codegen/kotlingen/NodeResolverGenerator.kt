package viaduct.tenant.codegen.kotlingen

import java.io.File
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.isNode
import viaduct.tenant.codegen.bytecode.config.tenantModule

private const val RESOLVER_DIRECTIVE = "resolver"

fun ViaductSchema.generateNodeResolvers(args: Args) {
    val gen = NodeResolverGenerator(
        this,
        args.tenantPackage,
        args.tenantPackagePrefix,
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
            .map { it.name }

        genNodeResolvers(tenantOwnedNodes, tenantPackage, grtPackage)?.let { contents ->
            val file = File(resolverGeneratedDir, "NodeResolvers.kt")
            contents.write(file)
        }
    }

    private fun isTenantOwnedNode(def: ViaductSchema.TypeDef): Boolean {
        return if (!isFeatureAppTest) {
            def.isNode && def.sourceLocation?.tenantModule == targetTenantModule
        } else {
            def.isNode
        }
    }

    private fun hasResolverDirective(def: ViaductSchema.TypeDef): Boolean = def.hasAppliedDirective(RESOLVER_DIRECTIVE)
}

internal fun genNodeResolvers(
    types: List<String>,
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
    val ctxInterface: String
    val selectiveCtxInterface: String
}

private class NodesModelImpl(override val tenantPackage: String, grtPackage: String, typeNames: List<String>) : NodesModel {
    override val nodes: List<NodeModel> = typeNames.map { NodeModelImpl(it, grtPackage) }
}

private class NodeModelImpl(override val typeName: String, override val grtPackage: String) : NodeModel {
    override val ctxInterface: String = "viaduct.api.context.NodeExecutionContext"
    override val selectiveCtxInterface: String = "viaduct.api.context.SelectiveNodeExecutionContext"
}

private val nodesSt = stTemplate(
    """
        package <mdl.tenantPackage>

        import viaduct.api.FieldValue
        import viaduct.api.SelectiveResolver
        import viaduct.api.internal.InternalContext
        import viaduct.api.internal.NodeResolverBase
        import viaduct.api.internal.NodeResolverFor
        import viaduct.api.select.SelectionSet

        object NodeResolvers {
            <mdl.nodes:node(); separator="\n">
        }
    """.trimIndent()
)

private val nodeSt = stTemplate(
    "node(mdl)",
    """
        @NodeResolverFor("<mdl.typeName>")
        abstract class <mdl.typeName> : NodeResolverBase\<<mdl.grtPackage>.<mdl.typeName>\> {
            open suspend fun resolve(ctx: Context): <mdl.grtPackage>.<mdl.typeName> =
                throw NotImplementedError("Nodes.<mdl.typeName>.resolve not implemented")

            open suspend fun batchResolve(contexts: List\<Context>): List\<FieldValue\<<mdl.grtPackage>.<mdl.typeName>\>> =
                throw NotImplementedError("Nodes.<mdl.typeName>.batchResolve not implemented")

            class Context(
                private val inner: <mdl.selectiveCtxInterface>\<<mdl.grtPackage>.<mdl.typeName>\>
            ) : <mdl.ctxInterface>\<<mdl.grtPackage>.<mdl.typeName>\> by inner, InternalContext by (inner as InternalContext) {
                context(SelectiveResolver)
                fun selections(): SelectionSet\<<mdl.grtPackage>.<mdl.typeName>\> = inner.selections()
            }
        }
    """.trimIndent()
)

private val stGroup = nodesSt + nodeSt
