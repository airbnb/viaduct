package viaduct.tenant.codegen.kotlingen.bytecode

import getEscapedFieldName
import viaduct.apiannotations.VisibleForTest
import viaduct.codegen.km.getterName
import viaduct.codegen.km.kotlinTypeString
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.codegen.utils.JavaName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.connectionEdgeTypeName
import viaduct.tenant.codegen.bytecode.config.hasConnectionDirective
import viaduct.tenant.codegen.bytecode.config.hasEdgeDirective
import viaduct.tenant.codegen.bytecode.config.isNode
import viaduct.tenant.codegen.bytecode.config.kmType
import viaduct.tenant.codegen.bytecode.config.typeOfNodeField

@VisibleForTest
fun KotlinGRTFilesBuilder.objectKotlinGen(typeDef: ViaductSchema.Object): STContents {
    val connectionInfo = resolveConnectionInfo(typeDef)

    val model = ObjectModelImpl(
        typeDef = typeDef,
        pkg = pkg,
        reflectedTypeGen = reflectedTypeGen(typeDef),
        baseTypeMapper = baseTypeMapper,
        isQueryType = typeDef.isQueryType(),
        isMutationType = typeDef.isMutationType(),
        connectionInfo = connectionInfo,
    )

    val template = if (connectionInfo != null) connectionObjectSTGroup else objectSTGroup
    return STContents(template, model)
}

/**
 * Resolves connection type metadata for a GraphQL object.
 * Returns null if the type does not have the connection directive.
 * Throws if the directive is present but the schema is malformed.
 */
private fun KotlinGRTFilesBuilder.resolveConnectionInfo(typeDef: ViaductSchema.Object): ConnectionInfo? {
    if (!typeDef.hasConnectionDirective) return null

    val edgeTypeName = checkNotNull(typeDef.connectionEdgeTypeName) {
        "Connection type '${typeDef.name}' has @connection directive but no edge type"
    }
    val edgeType = schema.types[edgeTypeName] as? ViaductSchema.Object
        ?: error("Connection type '${typeDef.name}' edge type '$edgeTypeName' is not an Object type")
    val nodeTypeName = checkNotNull(edgeType.typeOfNodeField) {
        "Edge type '$edgeTypeName' for connection '${typeDef.name}' has no 'node' field"
    }

    return ConnectionInfo(edgeTypeName, nodeTypeName)
}

/**
 * Type information for Connection types.
 * Note: nodeTypeName is needed for the Connection<E, N> supertype,
 * but ConnectionBuilder<C, E> infers N from E : Edge<N>.
 */
private data class ConnectionInfo(
    val edgeTypeName: String,
    val nodeTypeName: String
)

private interface ObjectModel {
    /** Package into which code will be generated. */
    val pkg: String

    /** Name of the class to be generated. */
    val className: String

    /** Comma-separated list of supertypes of this class. */
    val superTypes: String

    /** Submodels for each field. */
    val fields: List<FieldModel>

    /** A rendered template string that describes this type's Reflection object. */
    val reflection: String

    /** Whether this is a @connection type. */
    val isConnection: Boolean

    /** Edge type name for connection types. */
    val edgeTypeName: String?

    /** Node type name for connection types. */
    val nodeTypeName: String?

    class FieldModel(
        pkg: String,
        fieldDef: ViaductSchema.Field,
        baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper
    ) {
        val getterName: String = getterName(fieldDef.name)

        /** Field setter does not have prefix, just uses field name directly.
         * For fields whose names match Kotlin keywords (e.g., "private"),
         *  we need to use Kotlin's back-tick mechanism for escaping.
         */
        val escapedName: String = getEscapedFieldName(fieldDef.name)

        /** If field's getter is overriding a field from an implemented interface,
         *  then this string will be `final override`, otherwise it  will be
         *  the empty string.  ("Final" is needed because, in Kotlin, if you
         *  override an interface-function in an implementating class, then
         *  the function remains open, where for our GRTs we want all getters
         *  to be final.)
         */
        val overrideKeywords: String = if (fieldDef.isOverride) "final override" else ""

        /** Kotlin GRT-type of this field. */
        val kotlinType: String = fieldDef.kmType(JavaName(pkg).asKmName, baseTypeMapper).kotlinTypeString
    }
}

private val objectSTGroup = stTemplate(
    """
    @file:Suppress("warnings")

    package <mdl.pkg>

    import viaduct.apiannotations.InternalApi
    import viaduct.api.context.ExecutionContext
    import viaduct.api.internal.InternalContext
    import viaduct.api.internal.ObjectBase
    import viaduct.engine.api.EngineObject
    import viaduct.engine.api.EngineObjectData

    @OptIn(InternalApi::class)
    class <mdl.className>(context: InternalContext, engineObject: EngineObject)
        : ObjectBase(context, engineObject), <mdl.superTypes>
    {
        <mdl.fields: { f |
          <f.overrideKeywords> suspend fun <f.getterName>(alias: String?): <f.kotlinType> = TODO()
          <f.overrideKeywords> suspend fun <f.getterName>(): <f.kotlinType> = TODO()
        }; separator="\n">

        fun toBuilder(): Builder =
            Builder(context, engineObject.type, toBuilderEOD())

        class Builder : ObjectBase.Builder\<<mdl.className>\> {
            constructor(context: ExecutionContext)
                : super(
                    context as InternalContext,
                    TODO() as graphql.schema.GraphQLObjectType,
                    null
                )

            internal constructor(
                context: InternalContext,
                type: graphql.schema.GraphQLObjectType,
                baseEngineObjectData: EngineObjectData
            ) : super(context, type, baseEngineObjectData)

            <mdl.fields: { f |
              fun <f.escapedName>(value: <f.kotlinType>): Builder = TODO()
            }; separator="\n">

            final override fun build(): <mdl.className> = TODO()
        }

        <mdl.reflection>
    }
"""
)

private val connectionObjectSTGroup = stTemplate(
    """
    @file:Suppress("warnings")

    package <mdl.pkg>

    import viaduct.apiannotations.ExperimentalApi
    import viaduct.apiannotations.InternalApi
    import viaduct.api.context.ConnectionFieldExecutionContext
    import viaduct.api.internal.ConnectionBuilder
    import viaduct.api.internal.InternalContext
    import viaduct.api.internal.ObjectBase
    import viaduct.api.types.ConnectionArguments
    import viaduct.engine.api.EngineObject
    import viaduct.engine.api.EngineObjectData

    @OptIn(InternalApi::class, ExperimentalApi::class)
    class <mdl.className>(context: InternalContext, engineObject: EngineObject)
        : ObjectBase(context, engineObject), <mdl.superTypes>
    {
        <mdl.fields: { f |
          <f.overrideKeywords> suspend fun <f.getterName>(alias: String?): <f.kotlinType> = TODO()
          <f.overrideKeywords> suspend fun <f.getterName>(): <f.kotlinType> = TODO()
        }; separator="\n">

        fun toBuilder(): Builder =
            Builder(context, engineObject.type, toBuilderEOD())

        class Builder : ConnectionBuilder\<<mdl.className>, <mdl.pkg>.<mdl.edgeTypeName>, <mdl.pkg>.<mdl.nodeTypeName>\> {
            constructor(context: ConnectionFieldExecutionContext\<*, *, out ConnectionArguments, <mdl.className>\>)
                : super(
                    context,
                    TODO() as graphql.schema.GraphQLObjectType,
                    null,
                    <mdl.pkg>.<mdl.edgeTypeName>.Reflection
                )

            internal constructor(
                context: InternalContext,
                type: graphql.schema.GraphQLObjectType,
                baseEngineObjectData: EngineObjectData
            ) : super(
                    context as ConnectionFieldExecutionContext\<*, *, out ConnectionArguments, <mdl.className>\>,
                    type,
                    baseEngineObjectData,
                    <mdl.pkg>.<mdl.edgeTypeName>.Reflection
                )

            <mdl.fields: { f |
              fun <f.escapedName>(value: <f.kotlinType>): Builder = TODO()
            }; separator="\n">

            final override fun build(): <mdl.className> {
                @Suppress("UNUSED_EXPRESSION")
                ObjectBase.Builder::class
                return TODO()
            }
        }

        <mdl.reflection>
    }
"""
)

private class ObjectModelImpl(
    private val typeDef: ViaductSchema.Object,
    override val pkg: String,
    reflectedTypeGen: STContents,
    baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    private val isQueryType: Boolean,
    private val isMutationType: Boolean,
    private val connectionInfo: ConnectionInfo?,
) : ObjectModel {
    override val className: String get() = typeDef.name

    override val reflection: String = reflectedTypeGen.toString()

    override val isConnection: Boolean = connectionInfo != null

    override val edgeTypeName: String? = connectionInfo?.edgeTypeName

    override val nodeTypeName: String? = connectionInfo?.nodeTypeName

    override val superTypes: String = buildSupertypesList()

    override val fields: List<ObjectModel.FieldModel> =
        typeDef.fields.map { ObjectModel.FieldModel(pkg, it, baseTypeMapper) }

    private fun buildSupertypesList(): String {
        val result = mutableListOf<String>(cfg.OBJECT_GRT.toString())

        // Interface and union supertypes
        for (s in (typeDef.supers + typeDef.unions)) {
            result.add("$pkg.${s.name}")
        }

        // Standard marker interfaces
        if (typeDef.isNode) result.add(cfg.NODE_OBJECT_GRT.toString())
        if (isQueryType) result.add(cfg.QUERY_OBJECT_GRT.toString())
        if (isMutationType) result.add(cfg.MUTATION_OBJECT_GRT.toString())

        // Edge<N> for @edge types
        if (typeDef.hasEdgeDirective) {
            val nodeTypeName = typeDef.typeOfNodeField
            result.add("${cfg.EDGE_GRT}<$pkg.$nodeTypeName>")
        }

        // Connection<E, N> for @connection types
        connectionInfo?.let {
            result.add("${cfg.CONNECTION_GRT}<$pkg.${it.edgeTypeName}, $pkg.${it.nodeTypeName}>")
        }

        return result.joinToString(",")
    }
}
