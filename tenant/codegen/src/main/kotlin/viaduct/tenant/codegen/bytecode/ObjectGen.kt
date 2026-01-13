package viaduct.tenant.codegen.bytecode

import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.hasAnnotations
import kotlinx.metadata.isSuspend
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.km.CustomClassBuilder
import viaduct.codegen.km.boxedJavaName
import viaduct.codegen.km.checkNotNullParameterExpression
import viaduct.codegen.km.getterName
import viaduct.codegen.utils.Km
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.baseTypeKmType
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.codegenIncludedFields
import viaduct.tenant.codegen.bytecode.config.isNode
import viaduct.tenant.codegen.bytecode.config.kmType

internal fun GRTClassFilesBuilder.objectGenV2(def: ViaductSchema.Object) {
    val builder = this.kmClassFilesBuilder.customClassBuilder(
        ClassKind.CLASS,
        def.name.kmFQN(this.pkg),
    )
    ObjectClassGenV2(this, def, builder)

    this.objectBuilderGenV2(def, builder)
    this.reflectedTypeGen(def, builder)
}

/**
 * `ObjectClassGenV2` is a function masquerading as a class: We're calling this constructor for its
 * side-effects only, not for the resulting object it constructs.  We're using the properties initialized
 * by the initial call to the constructor as "locally-global" variables to minimize the number of parameters
 * we need to pass to helper functions (keeping the code a bit cleaner).
 */
private class ObjectClassGenV2(
    private val grtClassFilesBuilder: GRTClassFilesBuilder,
    private val def: ViaductSchema.Object,
    private val objectClass: CustomClassBuilder,
) {
    private val pkg = grtClassFilesBuilder.pkg
    private val baseTypeMapper = grtClassFilesBuilder.baseTypeMapper

    init {
        for (s in (def.supers + def.unions)) {
            objectClass.addSupertype(s.name.kmFQN(pkg).asType())
            grtClassFilesBuilder.addSchemaGRTReference(s)
        }
        if (def.isNode) {
            objectClass.addSupertype(cfg.NODE_OBJECT_GRT.asKmName.asType())
        }
        with(grtClassFilesBuilder) {
            if (def.isQueryType()) {
                objectClass.addSupertype(cfg.QUERY_OBJECT_GRT.asKmName.asType())
            }
            if (def.isMutationType()) {
                objectClass.addSupertype(cfg.MUTATION_OBJECT_GRT.asKmName.asType())
            }
        }

        objectClass
            .addSupertype(cfg.OBJECT_BASE.asKmName.asType())
            .addSupertype(cfg.OBJECT_GRT.asKmName.asType())
            .addPrimaryConstructor()
            .addFieldGetters()
            .addToBuilderFun()
    }

    private fun CustomClassBuilder.addPrimaryConstructor(): CustomClassBuilder {
        val kmConstructor = KmConstructor().also { constructor ->
            constructor.visibility = Visibility.PUBLIC
            constructor.hasAnnotations = false
            constructor.valueParameters.addAll(
                listOf(
                    KmValueParameter("context").also {
                        it.type = cfg.INTERNAL_CONTEXT.asKmName.asType()
                    },
                    KmValueParameter("engineObject").also {
                        it.type = cfg.ENGINE_OBJECT.asKmName.asType()
                    },
                )
            )
        }

        this.addConstructor(
            kmConstructor,
            superCall = "super($1, $2);",
            body = buildString {
                append("{\n")
                append(checkNotNullParameterExpression(cfg.INTERNAL_CONTEXT.asKmName.asType(), 1, "context"))
                append(checkNotNullParameterExpression(cfg.ENGINE_OBJECT.asKmName.asType(), 2, "engineObject"))
                append("}")
            }
        )
        return this
    }

    private fun CustomClassBuilder.addFieldGetters(): CustomClassBuilder {
        for (field in def.codegenIncludedFields) {
            // Generate two field getters per field.
            // One to handle alias param and fetch the field value.
            this.addFieldGetter(field)
            // The other is just to pass default null as alias and
            // invoke the first getter.
            this.addFieldGetterToPassDefaultValue(field)
        }
        return this
    }

    private fun CustomClassBuilder.addFieldGetter(field: ViaductSchema.Field) {
        grtClassFilesBuilder.addSchemaGRTReference(field.type.baseTypeDef)

        val kmFun = KmFunction(getterName(field.name)).also {
            it.visibility = Visibility.PUBLIC
            it.modality = Modality.FINAL
            it.isSuspend = true
            it.returnType = field.kmType(pkg, baseTypeMapper)
            it.valueParameters.add(
                KmValueParameter("alias").also {
                    it.type = Km.STRING.asNullableType()
                }
            )
        }

        this.addSuspendFunction(
            kmFun,
            returnTypeAsInputForSuspend = field.kmType(pkg, baseTypeMapper, isInput = true),
            body = buildString {
                append("{\n")
                append("return this.fetch(\n")
                append("\"${field.name}\", \n")
                append(
                    // class of field base type
                    "kotlin.jvm.internal.Reflection.getOrCreateKotlinClass((Class)${field.baseTypeKmType(pkg, baseTypeMapper).boxedJavaName()}.class), \n"
                )
                append("$1, \n") // alias
                append("$2);\n") // continuation
                append("}")
            }
        )
    }

    private fun CustomClassBuilder.addFieldGetterToPassDefaultValue(field: ViaductSchema.Field) {
        grtClassFilesBuilder.addSchemaGRTReference(field.type.baseTypeDef)

        val kmFun = KmFunction(getterName(field.name)).also {
            it.visibility = Visibility.PUBLIC
            it.modality = Modality.FINAL
            it.isSuspend = true
            it.returnType = field.kmType(pkg, baseTypeMapper)
        }

        this.addSuspendFunction(
            kmFun,
            returnTypeAsInputForSuspend = field.kmType(pkg, baseTypeMapper, isInput = true),
            body = buildString {
                append("{\n")
                append("return this.${getterName(field.name)}((String)null, $1);")
                append("}")
            }
        )
    }

    private fun CustomClassBuilder.addToBuilderFun(): CustomClassBuilder {
        val builderName = this.kmName.append(".Builder")
        val kmFun = KmFunction("toBuilder").also {
            it.visibility = Visibility.PUBLIC
            it.modality = Modality.FINAL
            it.returnType = builderName.asType()
        }

        this.addFunction(
            kmFun,
            body = buildString {
                append("{\n")
                append("return new ${builderName.asJavaName}(\n")
                append("    this.getContext(),\n")
                append("    this.getEngineObject().getGraphQLObjectType(),\n")
                append("    this.toBuilderEOD()\n")
                append(");\n")
                append("}")
            }
        )
        return this
    }
}
