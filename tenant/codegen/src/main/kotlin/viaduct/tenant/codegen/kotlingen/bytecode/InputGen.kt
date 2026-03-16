package viaduct.tenant.codegen.kotlingen.bytecode

import getEscapedFieldName
import viaduct.apiannotations.VisibleForTest
import viaduct.codegen.km.kotlinTypeString
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.codegen.utils.JavaName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.InputTypeFactoryConfig
import viaduct.tenant.codegen.bytecode.config.kmType
import viaduct.tenant.codegen.util.ConnectionArgumentsInfo

/**
 * Generates Kotlin source code for an Input or Arguments class.
 *
 * @param field The field whose arguments are being generated, if this is an Arguments type.
 *   Used to detect whether the field returns a Connection type and add the appropriate
 *   ConnectionArguments interface.
 */
@VisibleForTest
fun KotlinGRTFilesBuilder.inputKotlinGen(
    desc: InputTypeDescriptor,
    taggingInterface: String,
    field: ViaductSchema.Field? = null
): STContents {
    val connectionInfo = ConnectionArgumentsInfo.from(field)
    return STContents(
        inputSTGroup,
        InputModelImpl(
            pkg,
            desc.className,
            desc.fields,
            taggingInterface,
            desc.def?.let(::reflectedTypeGen),
            baseTypeMapper,
            connectionArgumentsSupertype = connectionInfo.interfaceToAdd?.let { ", ${it.asJavaName}" } ?: "",
            overrideFieldNames = connectionInfo.overrideFieldNames,
            containingField = desc.containingField,
        )
    )
}

private interface InputModel {
    /** Package into which code will be generated. */
    val pkg: String

    /** Name of the class to be generated. */
    val className: String

    /** Submodels for each field. */
    val fields: List<FieldModel>

    /** Tagging interface for this class, either Input or Arguments */
    val taggingInterface: String

    /** Complete InputTypeFactory call expression for the Builder constructor */
    val inputTypeCall: String

    /** A rendered template string that describes this types Reflection object */
    val reflection: String

    /**
     * Additional supertype for ConnectionArguments interfaces.
     * Either empty string or ", viaduct.api.types.ForwardConnectionArguments" etc.
     */
    val connectionArgumentsSupertype: String

    /** Submodel for "fields" in this type. */
    class FieldModel(
        pkg: String,
        fieldDef: ViaductSchema.HasDefaultValue,
        baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
        isOverride: Boolean = false
    ) {
        /** For fields whose names match Kotlin keywords (e.g., "private"),
         *  we need to use Kotlin's back-tick mechanism for escapsing.
         */
        val escapedName: String = getEscapedFieldName(fieldDef.name)

        /** Kotlin GRT-type of this field. */
        val kotlinType: String = fieldDef.kmType(JavaName(pkg).asKmName, baseTypeMapper).kotlinTypeString

        /** "final override" if this field overrides a ConnectionArguments interface property, empty otherwise. */
        val overrideKeyword: String = if (isOverride) "final override " else ""
    }
}

private val inputSTGroup =
    stTemplate(
        """
    @file:Suppress("warnings")

    package <mdl.pkg>

    import graphql.schema.GraphQLInputObjectType
    import viaduct.apiannotations.InternalApi
    import viaduct.api.context.ExecutionContext
    import viaduct.api.internal.InputTypeFactory
    import viaduct.api.internal.InternalContext
    import viaduct.api.internal.internal
    import viaduct.api.internal.InputLikeBase
    import viaduct.api.types.Input

    @OptIn(InternalApi::class)
    class <mdl.className> internal constructor(
        override val context: InternalContext,
        override val inputData: Map\<String, Any?>,
        override val graphQLInputObjectType: GraphQLInputObjectType,
    ): InputLikeBase(), <mdl.taggingInterface><mdl.connectionArgumentsSupertype> {
        init {
           TODO()
        }

        <mdl.fields: { f |
            <f.overrideKeyword>val <f.escapedName>: <f.kotlinType> get() = TODO()
        }; separator="\n">

        fun toBuilder() = Builder(context, graphQLInputObjectType, this.inputData.toMutableMap())

        class Builder internal constructor(
            override val context: InternalContext,
            override val graphQLInputObjectType: GraphQLInputObjectType,
            override val inputData: MutableMap\<String, Any?> = TODO()
        ) : InputLikeBase.Builder() {

            constructor(context: ExecutionContext): this(
                context.internal,
                <mdl.inputTypeCall>,
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
    baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    override val connectionArgumentsSupertype: String = "",
    overrideFieldNames: Set<String> = emptySet(),
    containingField: ViaductSchema.Field? = null,
) : InputModel {
    override val fields: List<InputModel.FieldModel> = fieldDefs.map {
        InputModel.FieldModel(pkg, it, baseTypeMapper, isOverride = it.name in overrideFieldNames)
    }
    override val reflection: String = reflectedType?.toString() ?: ""
    private val inputTypeMethod: String = InputTypeFactoryConfig.getFactoryMethodName(taggingInterface)
    override val inputTypeCall: String = if (containingField != null) {
        """InputTypeFactory.argumentsInputType("$className", "${containingField.containingDef.name}", "${containingField.name}", context.internal.schema)"""
    } else {
        """InputTypeFactory.$inputTypeMethod("$className", context.internal.schema)"""
    }
}
