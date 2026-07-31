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
import kotlinx.metadata.isNullable
import kotlinx.metadata.isSuspend
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.SchemaAnalysis
import viaduct.codegen.km.CustomClassBuilder
import viaduct.codegen.km.KmPropertyBuilder
import viaduct.codegen.km.castObjectExpression
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.kmType
import viaduct.tenant.codegen.util.ConnectionArgumentsInfo

internal fun GRTClassFilesBuilder.inputGen(def: ViaductSchema.Input) {
    makeInputClass(
        def.name.kmFQN(this.pkg),
        def.fields,
        cfg.INPUT_GRT.asKmName
    ).let {
        this.reflectedTypeGen(def, it)
        this.fieldsObjectGen(def, it)
    }
}

internal fun GRTClassFilesBuilder.fieldArgumentsInputGen(field: ViaductSchema.Field) {
    if (field.args.none()) return

    val connectionArgsInfo = ConnectionArgumentsInfo.from(field)
    val argumentsSimpleName = cfg.argumentTypeName(field)

    // The chosen ConnectionArguments interface declares getters for the whole pagination pair, but
    // the schema may declare only part of it (e.g. `first` without `after`). Synthesize the missing
    // counterparts so the generated class satisfies the interface. See
    // SchemaAnalysis.connectionArgumentRequiredNames.
    val declaredArgNames = field.args.map { it.name }.toSet()
    val synthesizedConnectionArgs =
        SchemaAnalysis.connectionArgumentRequiredNames(SchemaAnalysis.connectionArgumentsDirection(field)) - declaredArgNames

    val builder = makeInputClass(
        argumentsSimpleName.kmFQN(pkg),
        field.args,
        cfg.ARGUMENTS_GRT.asKmName,
        overrideFieldNames = connectionArgsInfo.overrideFieldNames,
        containingField = field,
        synthesizedConnectionArgs = synthesizedConnectionArgs,
    )

    // If the field returns a Connection type, add appropriate ConnectionArguments interface
    connectionArgsInfo.interfaceToAdd?.let { builder.addSupertype(it.asType()) }

    // Arguments GRTs have no backing TypeDef, but still get Reflection + Fields blocks generated
    // from their field-argument list so `SomeArguments.Fields.<arg>` descriptors exist for the
    // public Field.isPresent API.
    reflectedTypeGen(argumentsSimpleName, builder)
    fieldsObjectGen(argumentsSimpleName, field.args, builder)
}

private fun GRTClassFilesBuilder.makeInputClass(
    className: KmName,
    fields: Iterable<ViaductSchema.HasDefaultValue>,
    taggingInterface: KmName,
    overrideFieldNames: Set<String> = emptySet(),
    containingField: ViaductSchema.Field? = null,
    synthesizedConnectionArgs: Set<String> = emptySet(),
): CustomClassBuilder {
    val builder = kmClassFilesBuilder.customClassBuilder(
        ClassKind.CLASS,
        className
    )
    InputClassGen(this, fields, builder, overrideFieldNames, synthesizedConnectionArgs)
    builder.addSupertype(taggingInterface.asType())
    this.inputBuilderGen(fields, builder, taggingInterface, containingField)
    builder.addInputOfObject()

    return builder
}

private class InputClassGen(
    private val grtClassFilesBuilder: GRTClassFilesBuilderBase,
    private val fields: Iterable<ViaductSchema.HasDefaultValue>,
    private val inputClass: CustomClassBuilder,
    private val overrideFieldNames: Set<String> = emptySet(),
    private val synthesizedConnectionArgs: Set<String> = emptySet(),
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
            .addSynthesizedConnectionArgProperties()
            .addToBuilderFun()
    }

    /**
     * Emits null-returning getters for pagination arguments the field's `ConnectionArguments`
     * sub-interface requires but the schema does not declare (e.g. `after` on a `first`-only
     * field). Without these the generated class would not satisfy the interface (an
     * `AbstractMethodError`-prone class). The getter reads the absent field from the backing map,
     * which yields null. See [SchemaAnalysis.connectionArgumentRequiredNames].
     */
    private fun CustomClassBuilder.addSynthesizedConnectionArgProperties(): CustomClassBuilder {
        for (argName in synthesizedConnectionArgs) {
            val kind = SchemaAnalysis.connectionArgumentScalarKind(argName)
                ?: error("Not a pagination argument: $argName")
            val fieldType = when (kind) {
                viaduct.codegen.ConnectionArgScalarKind.INT -> Km.INT.asNullableType()
                viaduct.codegen.ConnectionArgScalarKind.STRING -> Km.STRING.asNullableType()
            }
            val kmProperty = KmPropertyBuilder(
                JavaIdName(argName),
                fieldType,
                fieldType,
                isVariable = false,
                constructorProperty = false
            ).apply {
                getterVisibility(Visibility.PUBLIC)
                propertyModality(Modality.FINAL)
                // Always null: the counterpart is not a schema-declared argument, so it can never be
                // present in a request, and the schema-validated get() would reject the unknown
                // field. Returning null satisfies the ConnectionArguments interface (e.g. a
                // first-only field's after cursor is treated as "start from the beginning").
                getterBody(
                    body = buildString {
                        append("{\n")
                        append("return ${castObjectExpression(fieldType, "null")};\n")
                        append("}")
                    }
                )
            }
            this.addProperty(kmProperty)
        }
        return this
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
                getterVisibility(Visibility.PUBLIC)
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
        // Connection argument override fields (e.g., first, after, last, before) must be
        // nullable in the schema to match the ConnectionArguments interface declarations
        // (e.g., ForwardConnectionArguments.first: Int?, after: String?). Non-nullable types
        // generate primitive JVM getters (int getFirst()) that don't satisfy the interface's
        // boxed method (Integer getFirst()), causing AbstractMethodError at runtime.
        if (field.name in overrideFieldNames) {
            require(fieldType.isNullable) {
                "Connection argument '${field.name}' must be nullable in the schema to satisfy " +
                    "the ConnectionArguments interface, but it is declared as non-null."
            }
        }
        val kmProperty = KmPropertyBuilder(
            JavaIdName(field.name),
            fieldType,
            fieldType,
            isVariable = false,
            constructorProperty = false
        ).apply {
            getterVisibility(Visibility.PUBLIC)
            if (field.name in overrideFieldNames) {
                propertyModality(Modality.FINAL)
            }
            getterBody(
                body = buildString {
                    append("{\n")
                    append("return ${castObjectExpression(fieldType, "this.get(\"${field.name}\")")};\n")
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
                append("java.util.LinkedHashMap inputDataCopy = new java.util.LinkedHashMap();\n")
                append("inputDataCopy.putAll(this.getInputData());\n")
                append("final ${builderName.asJavaName} builder = new ${builderName.asJavaName}(this.getContext(), this.getGraphQLInputObjectType(), inputDataCopy);\n")
                append("return builder;\n")
                append("}")
            }
        )
        return this
    }
}

private fun CustomClassBuilder.addInputOfObject() {
    addOfObject(cfg.EXECUTION_CONTEXT.asKmName.asType())
}
