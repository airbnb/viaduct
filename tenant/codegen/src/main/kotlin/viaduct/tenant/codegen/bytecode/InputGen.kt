package viaduct.tenant.codegen.bytecode

import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.KmVariance
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.hasAnnotations
import kotlinx.metadata.isSuspend
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.km.CustomClassBuilder
import viaduct.codegen.km.KmPropertyBuilder
import viaduct.codegen.km.boxedJavaName
import viaduct.codegen.km.castObjectExpression
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.baseTypeKmType
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.kmType

internal fun GRTClassFilesBuilder.inputGen(def: ViaductSchema.Input) {
    makeInputClass(
        def.name.kmFQN(this.pkg),
        def.fields,
        cfg.INPUT_GRT.asKmName
    ).let {
        this.reflectedTypeGen(def, it)
    }
}

internal fun GRTClassFilesBuilder.fieldArgumentsInputGen(field: ViaductSchema.Field) {
    if (field.args.none()) return

    makeInputClass(
        cfg.argumentTypeName(field).kmFQN(pkg),
        field.args,
        cfg.ARGUMENTS_GRT.asKmName
    )
}

private fun GRTClassFilesBuilder.makeInputClass(
    className: KmName,
    fields: Iterable<ViaductSchema.HasDefaultValue>,
    taggingInterface: KmName
): CustomClassBuilder {
    val builder = kmClassFilesBuilder.customClassBuilder(
        ClassKind.CLASS,
        className
    )
    InputClassGen(this, fields, builder)
    builder.addSupertype(taggingInterface.asType())
    this.inputBuilderGen(fields, builder, taggingInterface)

    return builder
}

private class InputClassGen(
    private val grtClassFilesBuilder: GRTClassFilesBuilderBase,
    private val fields: Iterable<ViaductSchema.HasDefaultValue>,
    private val inputClass: CustomClassBuilder,
) {
    private val pkg = grtClassFilesBuilder.pkg
    private val baseTypeMapper = grtClassFilesBuilder.baseTypeMapper
    private val contextType = cfg.INTERNAL_CONTEXT.asKmName.asType()
    private val graphQLInputObjectType = KmName("graphql/schema/GraphQLInputObjectType").asType()

    init {
        inputClass
            .addSupertype(cfg.INPUT_LIKE_BASE.asKmName.asType())
            .addContextProperty()
            .addInputDataProperty()
            .addGraphQLInputObjectTypeProperty()
            .addPrimaryConstructor()
            .addFieldProperties()
            .addToBuilderFun()
    }

    private fun CustomClassBuilder.addContextProperty(): CustomClassBuilder =
        addProperty(
            KmPropertyBuilder(
                JavaIdName("context"),
                contextType,
                contextType,
                isVariable = false,
                constructorProperty = true
            ).apply {
                getterVisibility(Visibility.PROTECTED)
                propertyModality(Modality.OPEN)
            }
        )

    private fun CustomClassBuilder.addInputDataProperty(): CustomClassBuilder {
        val inputDataType = Km.MAP.asType().also {
            it.arguments += KmTypeProjection(
                KmVariance.INVARIANT,
                Km.STRING.asType()
            )
            it.arguments += KmTypeProjection(
                KmVariance.INVARIANT,
                Km.ANY.asNullableType()
            )
        }

        this.addProperty(
            KmPropertyBuilder(
                JavaIdName("inputData"),
                inputDataType,
                inputDataType,
                isVariable = false,
                constructorProperty = true
            ).apply {
                getterVisibility(Visibility.PUBLIC)
                propertyModality(Modality.OPEN)
            }
        )

        return this
    }

    private fun CustomClassBuilder.addGraphQLInputObjectTypeProperty(): CustomClassBuilder =
        addProperty(
            KmPropertyBuilder(
                JavaIdName("graphQLInputObjectType"),
                graphQLInputObjectType,
                graphQLInputObjectType,
                isVariable = false,
                constructorProperty = true
            ).apply {
                getterVisibility(Visibility.PROTECTED)
                propertyModality(Modality.OPEN)
            }
        )

    private fun CustomClassBuilder.addPrimaryConstructor(): CustomClassBuilder {
        val kmConstructor = KmConstructor().apply {
            visibility = Visibility.INTERNAL
            hasAnnotations = false
            valueParameters.addAll(
                listOf(
                    KmValueParameter("context").apply {
                        type = contextType
                    },
                    KmValueParameter("inputData").apply {
                        type = Km.MAP.asType().also {
                            it.arguments += KmTypeProjection(
                                KmVariance.INVARIANT,
                                Km.STRING.asType()
                            )
                            it.arguments += KmTypeProjection(
                                KmVariance.OUT,
                                Km.ANY.asNullableType()
                            )
                        }
                    },
                    KmValueParameter("graphQLInputObjectType").apply {
                        type = graphQLInputObjectType
                    }
                )
            )
        }

        this.addConstructor(
            kmConstructor,
            body = buildString {
                append("{\n")
                append("this.context = $1;\n")
                append("this.inputData = $2;\n")
                append("this.graphQLInputObjectType = $3;\n")
                append("this.validateInputDataAndThrowAsFrameworkError();\n")
                append("}")
            }
        )

        return this
    }

    private fun CustomClassBuilder.addFieldProperties(): CustomClassBuilder {
        for (field in fields) {
            this.addFieldProperty(field)
        }
        return this
    }

    private fun CustomClassBuilder.addFieldProperty(field: ViaductSchema.HasDefaultValue) {
        grtClassFilesBuilder.addSchemaGRTReference(field.type.baseTypeDef)

        val fieldType = field.kmType(pkg, baseTypeMapper)
        val baseTypeName = field.baseTypeKmType(pkg, baseTypeMapper).boxedJavaName()
        val kmProperty = KmPropertyBuilder(
            JavaIdName(field.name),
            fieldType,
            fieldType,
            isVariable = false,
            constructorProperty = false
        ).apply {
            getterVisibility(Visibility.PUBLIC)
            getterBody(
                body = buildString {
                    append("{\n")
                    append("return ${castObjectExpression(fieldType, "this.get(\"${field.name}\", kotlin.jvm.internal.Reflection.getOrCreateKotlinClass((Class)$baseTypeName.class))")};\n")
                    append("}")
                }
            )
        }

        this.addProperty(kmProperty)
    }

    private fun CustomClassBuilder.addToBuilderFun(): CustomClassBuilder {
        val builderName = this.kmName.append(".Builder")
        val kmFun = KmFunction("toBuilder").also {
            it.visibility = Visibility.PUBLIC
            it.isSuspend = false
            it.modality = Modality.FINAL
            it.returnType = builderName.asType()
        }

        this.addFunction(
            kmFun,
            buildString {
                append("{\n")
                append("final ${builderName.asJavaName} builder = new ${builderName.asJavaName}(this.getContext(), this.getGraphQLInputObjectType());")
                append("this.setFieldsOnBuilder(builder);")
                append("return builder;\n")
                append("}")
            }
        )
        return this
    }
}
