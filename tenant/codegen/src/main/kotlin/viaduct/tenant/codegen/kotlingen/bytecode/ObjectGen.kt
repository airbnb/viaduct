package viaduct.tenant.codegen.kotlingen.bytecode

import getEscapedFieldName
import viaduct.apiannotations.TestingApi
import viaduct.codegen.km.getterName
import viaduct.codegen.km.kotlinTypeString
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.codegen.utils.JavaName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.isNode
import viaduct.tenant.codegen.bytecode.config.kmType

@TestingApi
fun KotlinGRTFilesBuilder.objectKotlinGen(typeDef: ViaductSchema.Object) =
    STContents(
        objectSTGroup,
        ObjectModelImpl(
            typeDef,
            pkg,
            reflectedTypeGen(typeDef),
            baseTypeMapper,
            isQueryType = typeDef.isQueryType(),
            isMutationType = typeDef.isMutationType()
        )
    )

private interface ObjectModel {
    /** Package into which code will be generated. */
    val pkg: String

    /** Name of the class to be generated. */
    val className: String

    /** Comma-separated list of supertypes of
     *  this class by virtue of GraphQL `implements`
     *  clauses in the GraphQL schema.
     */
    val superTypes: String

    /** Submodels for each field. */
    val fields: List<FieldModel>

    /** A rendered template string that describes this types Reflection object */
    val reflection: String

    /** Submodel for "fields" in this type. */
    class FieldModel(
        pkg: String,
        fieldDef: ViaductSchema.Field,
        baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper
    ) {
        /** Field getter name. */
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

private val objectSTGroup =
    stTemplate(
        """
    @file:Suppress("warnings")

    package <mdl.pkg>

    import viaduct.api.context.ExecutionContext
    import viaduct.api.internal.InternalContext
    import viaduct.api.internal.ObjectBase
    import viaduct.engine.api.EngineObject
    import viaduct.engine.api.EngineObjectData

    class <mdl.className>(context: InternalContext, engineObject: EngineObject)
        : ObjectBase(context, engineObject), <mdl.superTypes>
    {
        <mdl.fields: { f |
          <f.overrideKeywords> suspend fun <f.getterName>(alias: String?): <f.kotlinType> = TODO()
          <f.overrideKeywords> suspend fun <f.getterName>(): <f.kotlinType> = TODO()
        }; separator="\n">

        fun toBuilder(): Builder =
            Builder(context, engineObject.graphQLObjectType, toBuilderEOD())

        class Builder : ObjectBase.Builder\<<mdl.className>\> {
            constructor(context: ExecutionContext)
                : super(
                    context as InternalContext,
                    TODO() as graphql.schema.GraphQLObjectType,
                    null
                )

            internal constructor(
                context: InternalContext,
                graphQLObjectType: graphql.schema.GraphQLObjectType,
                baseEngineObjectData: EngineObjectData
            ) : super(context, graphQLObjectType, baseEngineObjectData)

            <mdl.fields: { f |
              fun <f.escapedName>(value: <f.kotlinType>): Builder = TODO()
            }; separator="\n">

            final override fun build(): <mdl.className> = TODO()
        }

        <mdl.reflection>
    }
"""
    )

private class ObjectModelImpl(
    private val typeDef: ViaductSchema.Object,
    override val pkg: String,
    reflectedType: STContents,
    baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    private val isQueryType: Boolean,
    private val isMutationType: Boolean
) : ObjectModel {
    override val className get() = typeDef.name

    override val reflection: String = reflectedType.toString()

    override val superTypes: String = run {
        val result = mutableListOf<String>(cfg.OBJECT_GRT.toString())
        for (s in (typeDef.supers + typeDef.unions)) {
            result.add("$pkg.${s.name}")
        }
        if (typeDef.isNode) result.add(cfg.NODE_OBJECT_GRT.toString())
        if (isQueryType) result.add(cfg.QUERY_OBJECT_GRT.toString())
        if (isMutationType) result.add(cfg.MUTATION_OBJECT_GRT.toString())
        result.joinToString(",")
    }

    override val fields: List<ObjectModel.FieldModel> = typeDef.fields.map { ObjectModel.FieldModel(pkg, it, baseTypeMapper) }
}
