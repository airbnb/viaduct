package viaduct.tenant.codegen.kotlingen

import java.io.File
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.graphql.schema.ViaductExtendedSchema
import viaduct.tenant.codegen.bytecode.config.isNode
import viaduct.tenant.codegen.bytecode.config.tenantModule

private const val RESOLVER_DIRECTIVE = "resolver"

fun ViaductExtendedSchema.generateNodeResolvers(args: Args) {
    val gen = NodeResolverGenerator(
        this,
        args.tenantPackage,
        args.grtPackage,
        args.resolverGeneratedDir,
        args.isFeatureAppTest
    )
    gen.generate()
}

private class NodeResolverGenerator(
    private val schema: ViaductExtendedSchema,
    private val tenantPackage: String,
    private val grtPackage: String,
    private val resolverGeneratedDir: File,
    private val isFeatureAppTest: Boolean
) {
    private val targetTenantModule = tenantPackage
        .replace("com.airbnb.viaduct.", "")
        .replace(".", "/")

    fun generate() {
        val tenantOwnedNodes = schema.types.values
            .filter {
                isTenantOwnedNode(it) && hasResolverDirective(it)
            }
            .map { it.name }

        genNodeResolvers(tenantOwnedNodes, tenantPackage, grtPackage)?.let { contents ->
            val file = File(resolverGeneratedDir, "Nodes.kt")
            contents.write(file)
        }
    }

    private fun isTenantOwnedNode(def: ViaductExtendedSchema.TypeDef): Boolean {
        return if (!isFeatureAppTest) {
            def.isNode && def.sourceLocation?.tenantModule == targetTenantModule
        } else {
            def.isNode
        }
    }

    private fun hasResolverDirective(def: ViaductExtendedSchema.TypeDef): Boolean = def.hasAppliedDirective(RESOLVER_DIRECTIVE)
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
    val ctxType: String
    val ctxInterface: String
}

private class NodesModelImpl(override val tenantPackage: String, grtPackage: String, typeNames: List<String>) : NodesModel {
    override val nodes: List<NodeModel> = typeNames.map { NodeModelImpl(it, grtPackage) }
}

private class NodeModelImpl(override val typeName: String, override val grtPackage: String) : NodeModel {
    override val ctxType: String
        get() = "viaduct.tenant.runtime.context.NodeExecutionContextImpl"
    override val ctxInterface: String
        get() = "viaduct.api.context.NodeExecutionContext"
}

private val nodesSt = stTemplate(
    """
        package <mdl.tenantPackage>

        import viaduct.api.internal.InternalContext
        import viaduct.api.internal.NodeResolverBase
        import viaduct.api.internal.NodeResolverFor
        import viaduct.api.FieldValue

        object Nodes {
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

            @JvmInline
            value class Context(
                private val inner: <mdl.ctxType>\<<mdl.grtPackage>.<mdl.typeName>\>
            ) : <mdl.ctxInterface>\<<mdl.grtPackage>.<mdl.typeName>\> by inner, InternalContext by inner
        }
    """.trimIndent()
)

private val stGroup = nodesSt + nodeSt
