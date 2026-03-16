package viaduct.tenant.codegen.kotlingen

import java.io.File
import viaduct.codegen.km.kotlinTypeString
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.codegen.utils.JavaName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.hasConnectionDirective
import viaduct.tenant.codegen.bytecode.config.kmType
import viaduct.tenant.codegen.bytecode.config.tenantModule
import viaduct.utils.string.capitalize

private const val RESOLVER_DIRECTIVE = "resolver"

fun ViaductSchema.generateFieldResolvers(args: Args) {
    FieldResolverGenerator(
        this,
        args.tenantPackage,
        args.tenantPackagePrefix,
        args.resolverGeneratedDir,
        args.grtPackage,
        args.isFeatureAppTest,
        args.baseTypeMapper,
        queryTypeName = this.queryTypeDef?.name,
        mutationTypeName = this.mutationTypeDef?.name
    ).generate()
}

private class FieldResolverGenerator(
    private val schema: ViaductSchema,
    private val tenantPackage: String,
    private val tenantPackagePrefix: String,
    private val resolverGeneratedDir: File,
    private val grtPackage: String,
    private val isFeatureAppTest: Boolean = false,
    private val baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    private val queryTypeName: String?,
    private val mutationTypeName: String?
) {
    fun generate() {
        val typeToFields = schema.types.values.associate { typeDef ->
            typeDef.name to tenantResolverFields(typeDef)
        }

        for ((typeName, fields) in typeToFields) {
            if (fields.isNullOrEmpty()) continue

            val contents = genResolver(typeName, fields, tenantPackage, grtPackage, baseTypeMapper, queryTypeName, mutationTypeName)
            val file = File(resolverGeneratedDir, "${typeName}Resolvers.kt")
            contents.write(file)
        }
    }

    private fun tenantResolverFields(typeDef: ViaductSchema.TypeDef): List<ViaductSchema.Field>? {
        if (typeDef !is ViaductSchema.Object) return null

        val targetTenantModule = tenantPackage.replace("$tenantPackagePrefix.", "").replace(".", "/")
        val resolverFields = mutableListOf<ViaductSchema.Field>()
        for (extension in typeDef.extensions) {
            // This check will not pass for feature test app run so just generate field resolvers
            // even if the tenantModule doesn't match for it
            if (!isFeatureAppTest) {
                if (extension.sourceLocation?.tenantModule != targetTenantModule) continue
            }
            resolverFields.addAll(extension.members.filter { it.hasAppliedDirective(RESOLVER_DIRECTIVE) })
        }
        return resolverFields
    }
}

// internal for testing
internal fun genResolver(
    typeName: String,
    fields: Collection<ViaductSchema.Field>,
    tenantPackage: String,
    grtPackage: String,
    baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    queryTypeName: String?,
    mutationTypeName: String?
): STContents = STContents(stGroup, ResolversModelImpl(tenantPackage, grtPackage, typeName, fields, baseTypeMapper, queryTypeName, mutationTypeName))

private interface ResolversModel {
    val pkg: String
    val typeName: String
    val resolvers: List<ResolverModel>
}

private interface ResolverModel {
    val gqlTypeName: String
    val gqlFieldName: String
    val resolverName: String
    val typeSpecifier: String
    val ctxInterface: String
    val includeBatchResolve: Boolean
}

private class ResolversModelImpl(
    tenantPackage: String,
    grtPackage: String,
    override val typeName: String,
    fields: Collection<ViaductSchema.Field>,
    baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    queryTypeName: String?,
    mutationTypeName: String?
) : ResolversModel {
    override val pkg: String = tenantPackage
    override val resolvers: List<ResolverModel> = fields.map { ResolverModelImpl(it, grtPackage, baseTypeMapper, queryTypeName, mutationTypeName) }
}

private class ResolverModelImpl(
    val field: ViaductSchema.Field,
    val grtPackage: String,
    val baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    val queryTypeName: String?,
    val mutationTypeName: String?
) : ResolverModel {
    override val gqlTypeName: String = this.field.containingDef.name
    override val gqlFieldName: String = this.field.name
    override val resolverName: String = gqlFieldName.capitalize()
    private val queryGrtTypeName: String = "$grtPackage.${queryTypeName ?: "Query"}"
    private val mutationGrtTypeName: String = if (mutationTypeName != null) "$grtPackage.$mutationTypeName" else "viaduct.api.types.Mutation"
    private val grtTypeName: String = "$grtPackage.$gqlTypeName"
    private val grtArgsName: String =
        if (field.hasArgs) {
            "$grtPackage.${gqlTypeName}_${gqlFieldName.capitalize()}_Arguments"
        } else {
            "viaduct.api.types.Arguments.NoArguments"
        }
    private val grtOutputName: String =
        if (field.type.baseTypeDef.isComposite) {
            "$grtPackage.${field.type.baseTypeDef.name}"
        } else {
            "viaduct.api.types.CompositeOutput.NotComposite"
        }

    /**
     * Checks if the field returns a Connection type (detected via @connection directive).
     * Connection types get special context handling with pagination utilities.
     */
    private val isConnectionField: Boolean = field.type.baseTypeDef.hasConnectionDirective

    override val typeSpecifier: String = field.kmType(JavaName(grtPackage).asKmName, baseTypeMapper).kotlinTypeString
    override val ctxInterface: String
        get() = when {
            mutationTypeName != null && this.field.containingDef.name == mutationTypeName ->
                "viaduct.api.context.MutationFieldExecutionContext<$queryGrtTypeName, $mutationGrtTypeName, $grtArgsName, $grtOutputName>"
            isConnectionField ->
                "viaduct.api.context.ConnectionFieldExecutionContext<$grtTypeName, $queryGrtTypeName, $grtArgsName, $grtOutputName>"
            else ->
                "viaduct.api.context.FieldExecutionContext<$grtTypeName, $queryGrtTypeName, $grtArgsName, $grtOutputName>"
        }
    override val includeBatchResolve: Boolean = this.field.containingDef.name != "Mutation"
}

private val resolversST = stTemplate(
    """
    package <mdl.pkg>.resolverbases

    import graphql.schema.GraphQLSchema
    import viaduct.apiannotations.InternalApi
    import viaduct.api.context.FieldExecutionContext
    import viaduct.api.internal.InternalContext
    import viaduct.api.ResolverBase
    import viaduct.api.internal.ResolverFor
    import viaduct.api.types.Arguments.NoArguments
    import viaduct.api.types.CompositeOutput
    import viaduct.api.FieldValue
    <mdl.nativeTypeImports; separator="\n">

    @OptIn(InternalApi::class)
    object <mdl.typeName>Resolvers {
        <mdl.resolvers:resolver(); separator="\n">
    }
    """
)

private val resolverST = stTemplate(
    "resolver(mdl)",
    """
    @ResolverFor(typeName = "<mdl.gqlTypeName>", fieldName = "<mdl.gqlFieldName>")
    abstract class <mdl.resolverName> : ResolverBase\<<mdl.typeSpecifier>\> {
        class Context(
            private val inner: <mdl.ctxInterface>
        ) : <mdl.ctxInterface> by inner, InternalContext by (inner as InternalContext)
        open suspend fun resolve(ctx: Context): <mdl.typeSpecifier> =
            throw NotImplementedError("<mdl.gqlTypeName>.<mdl.gqlFieldName>.resolve not implemented")
        <if(mdl.includeBatchResolve)>
        open suspend fun batchResolve(contexts: List\<Context>): List\<FieldValue\<<mdl.typeSpecifier>\>> =
            throw NotImplementedError("<mdl.gqlTypeName>.<mdl.gqlFieldName>.batchResolve not implemented")
        <endif>
    }
    """
)

private val stGroup = resolversST + resolverST
