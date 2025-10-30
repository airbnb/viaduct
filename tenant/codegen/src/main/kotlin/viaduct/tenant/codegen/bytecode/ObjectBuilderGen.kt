package viaduct.tenant.codegen.bytecode

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
import viaduct.codegen.km.boxingExpression
import viaduct.codegen.km.castObjectExpression
import viaduct.codegen.km.checkNotNullParameterExpression
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.name
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.codegenIncludedFields
import viaduct.tenant.codegen.bytecode.config.kmType

internal fun GRTClassFilesBuilder.objectBuilderGenV2(
    def: ViaductSchema.Object,
    container: CustomClassBuilder,
): CustomClassBuilder {
    val result = container.nestedClassBuilder(JavaIdName("Builder"))
    ObjectBuilderGenV2(
        grtClassFilesBuilder = this,
        def = def,
        builderClass = result,
        builderFor = container.kmType
    )
    return result
}

private class ObjectBuilderGenV2(
    private val grtClassFilesBuilder: GRTClassFilesBuilderBase,
    private val def: ViaductSchema.Object,
    private val builderClass: CustomClassBuilder,
    private val builderFor: KmType
) {
    private val pkg = grtClassFilesBuilder.pkg
    private val baseTypeMapper = grtClassFilesBuilder.baseTypeMapper

    init {
        builderClass
            .addSupertype(
                cfg.OBJECT_BASE_BUILDER.asKmName.asType()
                    .also { it.arguments += KmTypeProjection(KmVariance.INVARIANT, builderFor) }
            )
            .addPrimaryConstructor()
            .addSecondaryConstructorForToBuilder()
            .addFieldSetters()
            .addBuildFun()
    }

    private fun CustomClassBuilder.addPrimaryConstructor(): CustomClassBuilder {
        val contextType = cfg.EXECUTION_CONTEXT.asKmName.asType()
        val kmConstructor = KmConstructor().also { constructor ->
            constructor.visibility = Visibility.PUBLIC
            constructor.hasAnnotations = false
            constructor.isSecondary = true
            constructor.valueParameters.add(
                KmValueParameter("context").also {
                    it.type = contextType
                }
            )
        }
        this.addConstructor(
            kmConstructor,
            superCall = """
                super(
                    ${castObjectExpression(cfg.INTERNAL_CONTEXT.asKmName.asType(), "$1")},
                    (${castObjectExpression(cfg.INTERNAL_CONTEXT.asKmName.asType(), "$1")})
                        .getSchema()
                        .getSchema()
                        .getObjectType("${def.name}"),
                    null
                );
            """.trimIndent(),
            body = buildString {
                append("{\n")
                append(checkNotNullParameterExpression(contextType, 1, "context"))
                append("}")
            }
        )
        return this
    }

    private fun CustomClassBuilder.addSecondaryConstructorForToBuilder(): CustomClassBuilder {
        val kmConstructor = KmConstructor().also { constructor ->
            constructor.visibility = Visibility.INTERNAL
            constructor.hasAnnotations = false
            constructor.isSecondary = true
            constructor.valueParameters.addAll(
                listOf(
                    KmValueParameter("context").also {
                        it.type = cfg.INTERNAL_CONTEXT.asKmName.asType()
                    },
                    KmValueParameter("graphQLObjectType").also {
                        it.type = cfg.GRAPHQL_OBJECT_TYPE.asKmName.asType()
                    },
                    KmValueParameter("baseEngineObjectData").also {
                        it.type = cfg.ENGINE_OBJECT_DATA.asKmName.asType()
                    },
                )
            )
        }

        this.addConstructor(
            kmConstructor,
            superCall = "super($1, $2, $3);",
            body = buildString {
                append("{\n")
                append(checkNotNullParameterExpression(cfg.INTERNAL_CONTEXT.asKmName.asType(), 1, "context"))
                append(checkNotNullParameterExpression(cfg.GRAPHQL_OBJECT_TYPE.asKmName.asType(), 2, "graphQLObjectType"))
                append(checkNotNullParameterExpression(cfg.ENGINE_OBJECT_DATA.asKmName.asType(), 3, "baseEngineObjectData"))
                append("}")
            }
        )
        return this
    }

    private fun CustomClassBuilder.addFieldSetters(): CustomClassBuilder {
        for (field in def.codegenIncludedFields) {
            this.addFieldSetter(field)
        }
        return this
    }

    private fun CustomClassBuilder.addFieldSetter(field: ViaductSchema.Field) {
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
                append("this.putInternal(\"${field.name}\", $boxedValueExpr);\n")
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
                append("return new ${builderFor.name.asJavaBinaryName}(this.getContext(), this.buildEngineObjectData());\n")
                append("}")
            },
            bridgeParameters = setOf(-1)
        )
        return this
    }
}
