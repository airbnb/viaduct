package viaduct.tenant.codegen.kotlingen.bytecode

import getEscapedFieldName
import viaduct.apiannotations.TestingApi
import viaduct.codegen.km.kotlinTypeString
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.codegen.utils.JavaName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.InputTypeFactoryConfig
import viaduct.tenant.codegen.bytecode.config.kmType

@TestingApi
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

    /** InputTypeFactory method name (argumentsInputType or inputInputType) */
    val inputTypeMethod: String

    /** A rendered template string that describes this types Reflection object */
    val reflection: String

    /** Submodel for "fields" in this type. */
    class FieldModel(
        pkg: String,
        fieldDef: ViaductSchema.HasDefaultValue,
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
    import viaduct.api.internal.InputTypeFactory
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

        fun toBuilder() = Builder(context, graphQLInputObjectType, this.inputData.toMutableMap())

        class Builder internal constructor(
            override val context: InternalContext,
            override val graphQLInputObjectType: GraphQLInputObjectType,
            override val inputData: MutableMap\<String, Any?> = TODO()
        ) : InputLikeBase.Builder() {

            constructor(context: ExecutionContext): this(
                context.internal,
                InputTypeFactory.<mdl.inputTypeMethod>("<mdl.className>", context.internal.schema),
                mutableMapOf()
            )

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
    fieldDefs: Iterable<ViaductSchema.HasDefaultValue>,
    override val taggingInterface: String,
    reflectedType: STContents?,
    baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper
) : InputModel {
    override val fields: List<InputModel.FieldModel> = fieldDefs.map { InputModel.FieldModel(pkg, it, baseTypeMapper) }
    override val reflection: String = reflectedType?.toString() ?: ""
    override val inputTypeMethod: String = InputTypeFactoryConfig.getFactoryMethodName(taggingInterface)
}
