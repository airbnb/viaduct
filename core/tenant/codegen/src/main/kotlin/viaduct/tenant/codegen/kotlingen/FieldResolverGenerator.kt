package viaduct.tenant.codegen.kotlingen

import java.io.File
import viaduct.codegen.SchemaAnalysis
import viaduct.codegen.km.kotlinTypeString
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.codegen.utils.JavaName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.hasConnectionDirective
import viaduct.tenant.codegen.bytecode.config.isBatchingResolver
import viaduct.tenant.codegen.bytecode.config.isSelectiveResolver
import viaduct.tenant.codegen.bytecode.config.kmType
import viaduct.tenant.codegen.bytecode.config.mutationNamespaceTypeNames
import viaduct.tenant.codegen.bytecode.config.tenantModule

private const val RESOLVER_DIRECTIVE = "resolver"

fun ViaductSchema.generateFieldResolvers(args: Args) {
    FieldResolverGenerator(
        this,
        args.tenantPackage,
        args.tenantPackagePrefix,
        args.tenantSchemaModulePrefix,
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
    private val tenantSchemaModulePrefix: String?,
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

        val mutationNamespaceNames = schema.mutationNamespaceTypeNames()

        for ((typeName, fields) in typeToFields) {
            if (fields.isNullOrEmpty()) continue

            val contents = genResolver(typeName, fields, tenantPackage, grtPackage, baseTypeMapper, queryTypeName, mutationTypeName, mutationNamespaceNames)
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
                val extensionTenantModule = extension.sourceLocation?.tenantModule
                if (!tenantOwnsModule(extensionTenantModule, targetTenantModule, tenantSchemaModulePrefix)) continue
            }
            resolverFields.addAll(extension.members.filter { it.hasAppliedDirective(RESOLVER_DIRECTIVE) })
        }
        return resolverFields
    }
}

/**
 * A tenant normally owns exactly one schema module, but service blocks may own a whole subtree
 * like `entity/userblock/...` while still generating code into a service-owned package.
 */
internal fun tenantOwnsModule(
    sourceTenantModule: String?,
    targetTenantModule: String,
    tenantSchemaModulePrefix: String?,
): Boolean {
    if (sourceTenantModule == null) return false
    if (tenantSchemaModulePrefix != null) {
        return sourceTenantModule == tenantSchemaModulePrefix ||
            sourceTenantModule.startsWith("$tenantSchemaModulePrefix/")
    }
    return sourceTenantModule == targetTenantModule
}

// internal for testing
internal fun genResolver(
    typeName: String,
    fields: Collection<ViaductSchema.Field>,
    tenantPackage: String,
    grtPackage: String,
    baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    queryTypeName: String?,
    mutationTypeName: String?,
    mutationNamespaceNames: Set<String> = emptySet()
): STContents = STContents(stGroup, ResolversModelImpl(tenantPackage, grtPackage, typeName, fields, baseTypeMapper, queryTypeName, mutationTypeName, mutationNamespaceNames))

private interface ResolversModel {
    val pkg: String
    val typeName: String
    val resolvers: List<ResolverModel>
}

private interface ResolverModel {
    val gqlTypeName: String
    val gqlFieldName: String
    val resolverName: String
    val selective: Boolean
    val selectiveLiteral: String
    val batching: Boolean
    val batchingLiteral: String
    val typeSpecifier: String
    val ctxInterface: String
    val selectiveCtxInterface: String
    val ctxOutputType: String
    val resolverBase: String
    val resolverBaseGeneric: String
}

private class ResolversModelImpl(
    tenantPackage: String,
    grtPackage: String,
    override val typeName: String,
    fields: Collection<ViaductSchema.Field>,
    baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    queryTypeName: String?,
    mutationTypeName: String?,
    mutationNamespaceNames: Set<String>
) : ResolversModel {
    override val pkg: String = tenantPackage
    override val resolvers: List<ResolverModel> = fields.map { ResolverModelImpl(it, grtPackage, baseTypeMapper, queryTypeName, mutationTypeName, mutationNamespaceNames) }
}

private class ResolverModelImpl(
    val field: ViaductSchema.Field,
    val grtPackage: String,
    val baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    val queryTypeName: String?,
    val mutationTypeName: String?,
    val mutationNamespaceNames: Set<String>
) : ResolverModel {
    override val gqlTypeName: String = this.field.containingDef.name
    override val gqlFieldName: String = this.field.name
    override val resolverName: String = SchemaAnalysis.resolverClassName(gqlFieldName)

    /**
     * A field executes as a mutation if it lives on the root mutation type or on a
     * `@namespaceType` object reachable from it. Selective and batching resolvers are both
     * unsupported on such fields (a re-execution would re-run the side effects).
     */
    private fun isMutationSideType(name: String): Boolean = name == (mutationTypeName ?: "Mutation") || name in mutationNamespaceNames

    override val selective: Boolean = if (isMutationSideType(field.containingDef.name)) {
        require(!field.isSelectiveResolver) {
            "@resolver(isSelective: true) is not supported on mutation field ${field.containingDef.name}.${field.name}"
        }
        false
    } else {
        field.isSelectiveResolver
    }
    override val selectiveLiteral: String = selective.toString()
    override val batching: Boolean = if (isMutationSideType(field.containingDef.name)) {
        require(!field.isBatchingResolver) {
            "@resolver(isBatching: true) is not supported on mutation field ${field.containingDef.name}.${field.name}"
        }
        false
    } else {
        field.isBatchingResolver
    }

    override val batchingLiteral: String = batching.toString()
    private val queryGrtTypeName: String = "$grtPackage.${queryTypeName ?: "Query"}"
    private val mutationGrtTypeName: String = if (mutationTypeName != null) "$grtPackage.$mutationTypeName" else "viaduct.api.types.Mutation"
    private val grtTypeName: String = "$grtPackage.$gqlTypeName"
    private val grtArgsName: String =
        if (field.hasArgs) {
            "$grtPackage.${SchemaAnalysis.argumentsTypeName(gqlTypeName, gqlFieldName)}"
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
    override val ctxOutputType: String = grtOutputName
    override val ctxInterface: String
        get() = when {
            mutationTypeName != null && this.field.containingDef.name == mutationTypeName ->
                "viaduct.api.context.MutationFieldExecutionContext<$queryGrtTypeName, $mutationGrtTypeName, $grtArgsName, $grtOutputName>"
            isConnectionField ->
                "viaduct.api.context.ConnectionFieldExecutionContext<$grtTypeName, $queryGrtTypeName, $grtArgsName, $grtOutputName>"
            else ->
                "viaduct.api.context.FieldExecutionContext<$grtTypeName, $queryGrtTypeName, $grtArgsName, $grtOutputName>"
        }
    override val selectiveCtxInterface: String = "viaduct.api.context.SelectiveFieldExecutionContext<$grtOutputName>"
    override val resolverBase: String
        get() = when {
            mutationTypeName != null && this.field.containingDef.name == mutationTypeName ->
                "viaduct.api.MutationResolverBase<$queryGrtTypeName, $mutationGrtTypeName, $grtArgsName, $typeSpecifier>"
            isConnectionField ->
                "viaduct.api.ConnectionResolverBase<$grtTypeName, $queryGrtTypeName, $grtArgsName, $typeSpecifier>"
            else ->
                "viaduct.api.FieldResolverBase<$grtTypeName, $queryGrtTypeName, $grtArgsName, $typeSpecifier>"
        }
    override val resolverBaseGeneric: String = "viaduct.api.ResolverBase<$typeSpecifier>"
}

private val resolversST = stTemplate(
    """
    package <mdl.pkg>.resolverbases

    import graphql.schema.GraphQLSchema
    import viaduct.apiannotations.ExperimentalApi
    import viaduct.apiannotations.InternalApi
    import viaduct.api.context.FieldExecutionContext
    import viaduct.api.internal.InternalContext
    import viaduct.api.internal.ResolverFor
    import viaduct.api.types.Arguments.NoArguments
    import viaduct.api.types.CompositeOutput
    import viaduct.api.FieldValue
    <mdl.nativeTypeImports; separator="\n">

    @OptIn(InternalApi::class, ExperimentalApi::class)
    object <mdl.typeName>Resolvers {
        <mdl.resolvers:resolver(); separator="\n">
    }
    """
)

private val resolverST = stTemplate(
    "resolver(mdl)",
    """
    @ResolverFor(typeName = "<mdl.gqlTypeName>", fieldName = "<mdl.gqlFieldName>", isSelective = <mdl.selectiveLiteral>, isBatching = <mdl.batchingLiteral>)
    abstract class <mdl.resolverName> : <mdl.resolverBaseGeneric>, <mdl.resolverBase><if(mdl.batching)>, viaduct.api.internal.BaseBatchedFieldResolver<else>, viaduct.api.internal.BaseUnbatchedFieldResolver<endif> {
        class Context(
            private val inner: <mdl.ctxInterface>
        ) : <mdl.ctxInterface> by inner<if(mdl.selective)>, <mdl.selectiveCtxInterface><endif>, InternalContext by (inner as InternalContext) {
            <if(mdl.selective)>
            @Suppress("UNCHECKED_CAST")
            override fun selections(): viaduct.api.select.SelectionSet\<<mdl.ctxOutputType>\> =
                (inner as <mdl.selectiveCtxInterface>).selections()
            <endif>
        }
        <if(!mdl.batching)>
        abstract suspend fun resolve(ctx: Context): <mdl.typeSpecifier>

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeFieldResolver(
            context: viaduct.api.context.BaseFieldExecutionContext\<*, *, *>
        ): Any? = resolve(Context(context as <mdl.ctxInterface>))
        <endif>
        <if(mdl.batching)>
        abstract suspend fun batchResolve(contexts: List\<Context>): List\<FieldValue\<<mdl.typeSpecifier>\>>

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeFieldBatchResolver(
            contexts: List\<viaduct.api.context.BaseFieldExecutionContext\<*, *, *\>>
        ): Any? = batchResolve(contexts.map { Context(it as <mdl.ctxInterface>) })
        <endif>
    }
    """
)

private val stGroup = resolversST + resolverST
