package viaduct.tenant.codegen.kotlingen.bytecode

import getEscapedFieldName
import viaduct.codegen.km.kotlinTypeString
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.codegen.utils.JavaName
import viaduct.graphql.schema.ViaductExtendedSchema
import viaduct.tenant.codegen.bytecode.config.kmType

fun KotlinGRTFilesBuilder.inputKotlinGen(
    desc: InputTypeDescriptor,
    taggingInterface: String
) = STContents(
    inputSTGroup,
    InputModelImpl(
        pkg,
        desc.className,
        desc.fields,
        taggingInterface,
        desc.def?.let(::reflectedTypeGen),
        baseTypeMapper
    )
)

private interface InputModel {
    /** Packege into which code will be generated. */
    val pkg: String

    /** Name of the class to be generated. */
    val className: String

    /** Submodels for each field. */
    val fields: List<FieldModel>

    /** Tagging interface for this class, either Input or Arguments */
    val taggingInterface: String

    /** A rendered template string that describes this types Reflection object */
    val reflection: String

    /** Submodel for "fields" in this type. */
    class FieldModel(
        pkg: String,
        fieldDef: ViaductExtendedSchema.HasDefaultValue,
        baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper
    ) {
        /** For fields whose names match Kotlin keywords (e.g., "private"),
         *  we need to use Kotlin's back-tick mechanism for escapsing.
         */
        val escapedName: String = getEscapedFieldName(fieldDef.name)

        /** Kotlin GRT-type of this field. */
        val kotlinType: String = fieldDef.kmType(JavaName(pkg).asKmName, baseTypeMapper).kotlinTypeString
    }
}

private val inputSTGroup =
    stTemplate(
        """
    @file:Suppress("warnings")

    package <mdl.pkg>

    import graphql.schema.GraphQLInputObjectType
    import viaduct.api.context.ExecutionContext
    import viaduct.api.internal.InternalContext
    import viaduct.api.internal.internal
    import viaduct.api.internal.InputLikeBase
    import viaduct.api.types.Input

    class <mdl.className> internal constructor(
        override val context: InternalContext,
        override val inputData: Map\<String, Any?>,
        override val graphQLInputObjectType: GraphQLInputObjectType,
    ): InputLikeBase(), <mdl.taggingInterface> {
        init {
           TODO()
        }

        <mdl.fields: { f |
            val <f.escapedName>: <f.kotlinType> get() = TODO()
        }; separator="\n">

        fun toBuilder(): Builder {
            val builder = Builder(context, graphQLInputObjectType)
            setFieldsOnBuilder(builder)
            return builder
        }

        class Builder internal constructor(
            override val context: InternalContext,
            override val graphQLInputObjectType: GraphQLInputObjectType,
        ) : InputLikeBase.Builder() {

            constructor(context: ExecutionContext): this(
                context.internal,
                <mdl.taggingInterface>.inputType("<mdl.className>", context.internal.schema)
            )

            override val inputData: MutableMap\<String, Any?> = TODO()

            init {
                TODO()
            }

            <mdl.fields: { f |
                fun <f.escapedName>(value: <f.kotlinType>): Builder = TODO()
            }; separator="\n">

            fun build(): <mdl.className> = TODO()
        }

        <mdl.reflection>
    }
"""
    )

private class InputModelImpl(
    override val pkg: String,
    override val className: String,
    fieldDefs: Iterable<ViaductExtendedSchema.HasDefaultValue>,
    override val taggingInterface: String,
    reflectedType: STContents?,
    baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper
) : InputModel {
    override val fields: List<InputModel.FieldModel> = fieldDefs.map { InputModel.FieldModel(pkg, it, baseTypeMapper) }
    override val reflection: String = reflectedType?.toString() ?: ""
}
