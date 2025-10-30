package viaduct.tenant.codegen.bytecode

import graphql.language.Value
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmType
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.KmVariance
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.hasAnnotations
import kotlinx.metadata.isNullable
import kotlinx.metadata.isSecondary
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.km.CustomClassBuilder
import viaduct.codegen.km.KmPropertyBuilder
import viaduct.codegen.km.boxingExpression
import viaduct.codegen.km.castObjectExpression
import viaduct.codegen.km.checkNotNullParameterExpression
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.codegen.utils.name
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.ValueConverter
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.kmType

internal fun GRTClassFilesBuilder.inputBuilderGen(
    fields: Iterable<ViaductSchema.HasDefaultValue>,
    container: CustomClassBuilder,
    inputTaggingInterface: KmName
): CustomClassBuilder {
    val result = container.nestedClassBuilder(JavaIdName("Builder"))
    InputBuilderGenV2(
        grtClassFilesBuilder = this,
        fields = fields,
        builderBuilder = container,
        builderClass = result,
        builderFor = container.kmType,
        inputTaggingInterface = inputTaggingInterface
    )
    return result
}

private class InputBuilderGenV2(
    private val grtClassFilesBuilder: GRTClassFilesBuilderBase,
    private val fields: Iterable<ViaductSchema.HasDefaultValue>,
    private val builderBuilder: CustomClassBuilder,
    private val builderClass: CustomClassBuilder,
    private val builderFor: KmType,
    private val inputTaggingInterface: KmName
) {
    private val pkg = grtClassFilesBuilder.pkg
    private val baseTypeMapper = grtClassFilesBuilder.baseTypeMapper

    private val internalContextType = cfg.INTERNAL_CONTEXT.asKmName.asType()
    private val executionContextType = cfg.EXECUTION_CONTEXT.asKmName.asType()
    private val graphQLInputObjectType = KmName("graphql/schema/GraphQLInputObjectType").asType()

    init {
        builderClass
            .addSupertype(cfg.INPUT_LIKE_BASE_BUILDER.asKmName.asType())
            .addContextProperty()
            .addInputDataProperty()
            .addGraphQLInputObjectTypeProperty()
            .addInternalConstructorWithContextAndGraphqlType()
            .addPublicConstructorWithContext()
            .addFieldSetters()
            .addBuildFun()
    }

    private fun CustomClassBuilder.addContextProperty(): CustomClassBuilder =
        addProperty(
            KmPropertyBuilder(
                JavaIdName("context"),
                internalContextType,
                internalContextType,
                isVariable = false,
                constructorProperty = true
            ).apply {
                getterVisibility(Visibility.PROTECTED)
                propertyModality(Modality.OPEN)
            }
        )

    private fun CustomClassBuilder.addInputDataProperty(): CustomClassBuilder {
        val inputDataType = Km.MUTABLE_MAP.asType().also {
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
                constructorProperty = false
            ).apply {
                getterVisibility(Visibility.PROTECTED)
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

    private fun CustomClassBuilder.addInternalConstructorWithContextAndGraphqlType(): CustomClassBuilder {
        val kmConstructor = KmConstructor().apply {
            visibility = Visibility.INTERNAL
            hasAnnotations = false
            isSecondary = false
            valueParameters.addAll(
                listOf(
                    KmValueParameter("context").apply {
                        type = internalContextType
                    },
                    KmValueParameter("graphQLInputObjectType").apply {
                        type = graphQLInputObjectType
                    },
                )
            )
        }

        val defaultFields = fields.filter { it.hasDefault }

        this.addConstructor(
            kmConstructor,
            body = buildString {
                append("{\n")
                append(checkNotNullParameterExpression(internalContextType, 1, "context"))
                append("\n")
                append(checkNotNullParameterExpression(graphQLInputObjectType, 2, "graphQLInputObjectType"))
                append("\n")
                append("this.context = $1;\n")
                append("this.graphQLInputObjectType = $2;\n")
                append("this.inputData = new java.util.LinkedHashMap();\n")

                defaultFields.forEach { field ->
                    val defaultValue = field.defaultValue?.let { ValueConverter.standard.convert(field.type, it as Value<*>) }
                    append("${generatePutFieldNameWithValue("this.inputData", field, defaultValue)}\n")
                }

                append("}")
            }
        )

        return this
    }

    private fun generatePutFieldNameWithValue(
        mapName: String,
        field: ViaductSchema.HasDefaultValue,
        value: Any?
    ): String {
        if (value == null) {
            return "$mapName.put(\"${field.name}\", null);\n"
        }

        // If the field type is Input, its default value `value` is a raw value map.
        // We'll use this map as the default value for this field.
        if (field.type.baseTypeDef is ViaductSchema.Input) {
            val fieldMapName = "${field.name}Map"
            val t = field.type.baseTypeDef as ViaductSchema.Input
            @Suppress("UNCHECKED_CAST")
            val v = (value as Map<String, Any>).mapNotNull { e ->
                if (e.key == "__typename") {
                    return@mapNotNull null
                }
                val f = t.field(e.key)!! as ViaductSchema.HasDefaultValue
                generatePutFieldNameWithValue(fieldMapName, f, e.value)
            }.joinToString("\n")

            return """
                java.util.LinkedHashMap $fieldMapName = new java.util.LinkedHashMap();
                $v
                $mapName.put("${field.name}", $fieldMapName);
                """.trimIndent()
        }

        val valueExp = boxedValueCtExpression(field.type, value)
        return "$mapName.put(\"${field.name}\", $valueExp);\n"
    }

    /**
     * Given field type and its value (which complies to its type), return the boxed value expression for Ct syntax.
     */
    private fun boxedValueCtExpression(
        fieldType: ViaductSchema.TypeExpr,
        value: Any?
    ): String {
        val vExp = fieldType.valueInCtSyntax(value, pkg)
        return fieldType.ctBoxedExpr(vExp)
    }

    private fun CustomClassBuilder.addPublicConstructorWithContext(): CustomClassBuilder {
        val kmConstructor = KmConstructor().apply {
            visibility = Visibility.PUBLIC
            hasAnnotations = false
            isSecondary = true
            valueParameters.add(
                KmValueParameter("context").apply {
                    type = executionContextType
                }
            )
        }
        val className = builderBuilder.kmType.name.toString().split("/").last()

        this.addConstructor(
            kmConstructor,
            superCall = """
                this(
                    ${castObjectExpression(internalContextType, "$1")},
                    ${inputTaggingInterface.asJavaName}.Companion.inputType(
                        "$className",
                        (${castObjectExpression(internalContextType, "$1")}).getSchema()
                    )
                );
            """.trimIndent(),
            body = buildString {
                append("{\n")
                append(checkNotNullParameterExpression(executionContextType, 1, "context"))
                append("}")
            }
        )

        return this
    }

    private fun CustomClassBuilder.addFieldSetters(): CustomClassBuilder {
        for (field in fields) {
            this.addFieldSetter(field)
        }
        return this
    }

    private fun CustomClassBuilder.addFieldSetter(field: ViaductSchema.HasDefaultValue) {
        grtClassFilesBuilder.addSchemaGRTReference(field.type.baseTypeDef)

        val fieldKmType = field.kmType(pkg, baseTypeMapper, isInput = true)

        val kmFun = KmFunction(field.name).also {
            it.visibility = Visibility.PUBLIC
            it.modality = Modality.FINAL
            it.returnType = this.kmType
        }

        kmFun.valueParameters.add(
            KmValueParameter("value").also {
                it.type = fieldKmType
            }
        )

        this.addFunction(
            kmFun,
            body = buildString {
                append("{\n")
                val boxedValueExpr = boxingExpression(fieldKmType, "$1")
                if (!fieldKmType.isNullable) {
                    append("kotlin.jvm.internal.Intrinsics.checkNotNullParameter($boxedValueExpr, \"value\");\n")
                }
                append("this.put(\"${field.name}\", $boxedValueExpr);\n")
                append("return this;\n")
                append("}")
            }
        )
    }

    private fun CustomClassBuilder.addBuildFun(): CustomClassBuilder {
        val kmFun = KmFunction("build").also {
            it.visibility = Visibility.PUBLIC
            it.modality = Modality.FINAL
            it.returnType = builderFor
        }

        this.addFunction(
            kmFun,
            body = buildString {
                append("{\n")
                append("this.validateInputDataAndThrowAsTenantError();\n")
                append("return new ${builderFor.name.asJavaBinaryName}(this.getContext(), this.getInputData(), this.getGraphQLInputObjectType());\n")
                append("}")
            }
        )
        return this
    }
}
