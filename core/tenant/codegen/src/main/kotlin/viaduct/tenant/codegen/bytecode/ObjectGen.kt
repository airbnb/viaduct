package viaduct.tenant.codegen.bytecode

import kotlinx.metadata.ClassKind
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
import kotlinx.metadata.isSuspend
import kotlinx.metadata.jvm.annotations
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.GeneratedAccessorNames
import viaduct.codegen.ct.javaTypeName
import viaduct.codegen.km.CustomClassBuilder
import viaduct.codegen.km.KmPropertyBuilder
import viaduct.codegen.km.boxedJavaName
import viaduct.codegen.km.castObjectExpression
import viaduct.codegen.km.checkNotNullParameterExpression
import viaduct.codegen.km.checkNotNullParameterExpressions
import viaduct.codegen.km.getterName
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.AccessorForm
import viaduct.tenant.codegen.bytecode.config.baseTypeKmType
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.codegenIncludedFields
import viaduct.tenant.codegen.bytecode.config.connectionEdgeTypeName
import viaduct.tenant.codegen.bytecode.config.hasConnectionDirective
import viaduct.tenant.codegen.bytecode.config.hasEdgeDirective
import viaduct.tenant.codegen.bytecode.config.isNode
import viaduct.tenant.codegen.bytecode.config.kmType
import viaduct.tenant.codegen.bytecode.config.rootFieldReferenceFields
import viaduct.tenant.codegen.bytecode.config.typeOfNodeField

internal fun GRTClassFilesBuilder.objectGenV2(def: ViaductSchema.Object) {
    val builder = this.kmClassFilesBuilder.customClassBuilder(
        ClassKind.CLASS,
        def.name.kmFQN(this.pkg),
    )
    ObjectClassGenV2(this, def, builder)

    this.objectBuilderGenV2(def, builder)
    this.reflectedTypeGen(def, builder)
    this.fieldsObjectGen(def, builder)
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

    private data class ReturnTypeBridge(
        val returnType: KmType,
        val body: String
    )

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

        // Add Connection<E, N> marker interface for @connection directive types
        if (def.hasConnectionDirective) {
            addConnectionInterface()
        }

        // Add Edge<N> marker interface for @edge directive types
        if (def.hasEdgeDirective) {
            addEdgeInterface()
        }

        objectClass
            .addSupertype(cfg.OBJECT_BASE.asKmName.asType())
            .addSupertype(cfg.OBJECT_GRT.asKmName.asType())
            .addPrimaryConstructor()
            .addFieldGetters()
            .addToBuilderFun()
            .addOfObject()
            .addRootFieldReferences()
    }

    /**
     * Add Connection<EdgeType, NodeType> interface to the class.
     * The edge type is extracted from the 'edges' field, and the node type
     * is extracted from the edge's 'node' field.
     */
    private fun addConnectionInterface() {
        val edgeTypeName = def.connectionEdgeTypeName ?: return
        val edgeTypeDef = grtClassFilesBuilder.getType(edgeTypeName) as? ViaductSchema.Object ?: return
        val nodeTypeName = edgeTypeDef.typeOfNodeField

        val edgeKmType = KmName("$pkg/$edgeTypeName").asType()
        val nodeKmType = KmName("$pkg/$nodeTypeName").asType()

        val connectionType = cfg.CONNECTION_GRT.asKmName.asType().also {
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, edgeKmType)
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, nodeKmType)
        }
        objectClass.addSupertype(connectionType)
    }

    /**
     * Add Edge<NodeType> interface to the class.
     * The node type is extracted from the 'node' field.
     */
    private fun addEdgeInterface() {
        val nodeTypeName = def.typeOfNodeField

        val nodeKmType = KmName("$pkg/$nodeTypeName").asType()

        val edgeType = cfg.EDGE_GRT.asKmName.asType().also {
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, nodeKmType)
        }
        objectClass.addSupertype(edgeType)
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
        GeneratedAccessorNames.validateNoCollisions(
            def.name,
            def.codegenIncludedFields.associate { it.name to getterName(it.name) },
            cfg.FIELD_ACCESSOR_SUFFIXES
        )

        for (field in def.codegenIncludedFields) {
            for (form in AccessorForm.entries) {
                this.addFieldGetter(field, form)
                this.addFieldGetterToPassDefaultValue(field, form)
            }
        }
        return this
    }

    private fun CustomClassBuilder.addFieldGetter(
        field: ViaductSchema.Field,
        form: AccessorForm
    ) {
        grtClassFilesBuilder.addSchemaGRTReference(field.type.baseTypeDef)

        val methodName = form.methodName(getterName(field.name))

        val kmFun = KmFunction(methodName).apply {
            visibility = Visibility.PUBLIC
            modality = Modality.FINAL
            isSuspend = false
            returnType = field.kmType(pkg, baseTypeMapper).also { t ->
                if (form.nullable) t.isNullable = true
            }
            valueParameters.add(
                KmValueParameter("alias").apply {
                    type = Km.STRING.asNullableType()
                }
            )
        }

        val returnType = field.kmType(pkg, baseTypeMapper).also { t ->
            if (form.nullable) t.isNullable = true
        }
        val fetchExpr = field.fetchExpression("$1", form.fetchMethod)
        val bridge = field.covariantReturnTypeBridge(returnType, fetchExpr)
        this.addFunctionWithReturnTypeBridge(
            kmFun,
            body = returnBody(returnType, fetchExpr),
            bridge = bridge,
        )
    }

    private fun CustomClassBuilder.addFieldGetterToPassDefaultValue(
        field: ViaductSchema.Field,
        form: AccessorForm
    ) {
        grtClassFilesBuilder.addSchemaGRTReference(field.type.baseTypeDef)

        val methodName = form.methodName(getterName(field.name))

        val kmFun = KmFunction(methodName).also {
            it.visibility = Visibility.PUBLIC
            it.modality = Modality.FINAL
            it.isSuspend = false
            it.returnType = field.kmType(pkg, baseTypeMapper).also { t ->
                if (form.nullable) t.isNullable = true
            }
        }

        val bridge = field.covariantReturnTypeBridge(kmFun.returnType, field.fetchExpression("(String)null", form.fetchMethod))
        this.addFunctionWithReturnTypeBridge(
            kmFun,
            body = buildString {
                append("{\n")
                append("return this.$methodName((String)null);")
                append("}")
            },
            bridge = bridge,
        )
    }

    private fun CustomClassBuilder.addFunctionWithReturnTypeBridge(
        function: KmFunction,
        body: String,
        bridge: ReturnTypeBridge?
    ) {
        this.addFunction(
            function,
            body = body,
            bridgeParameters = bridge?.let { setOf(-1) } ?: emptySet(),
            bridgeReturnType = bridge?.returnType,
            bridgeBody = bridge?.body,
        )
    }

    private fun returnBody(
        returnType: KmType,
        expression: String
    ): String =
        buildString {
            append("{\n")
            val castExpression =
                if (returnType.isNullable) "(${returnType.boxedJavaName()})$expression" else castObjectExpression(returnType, expression)
            append("return $castExpression;\n")
            append("}")
        }

    private fun ViaductSchema.Field.fetchExpression(
        aliasExpression: String,
        fetchMethod: String = "getInternal"
    ): String =
        buildString {
            append("this.$fetchMethod(\n")
            append("\"$name\", \n")
            append(
                // class of field base type
                "kotlin.jvm.internal.Reflection.getOrCreateKotlinClass((Class)${baseTypeKmType(pkg, baseTypeMapper).boxedJavaName()}.class), \n"
            )
            append("$aliasExpression)")
        }

    private fun ViaductSchema.Field.covariantOverrideBridgeReturnType(returnKmType: KmType): KmType? {
        if (!isOverride) return null
        val overriddenField = def.findOverriddenFieldWithDifferentType(name, returnKmType) ?: return null
        grtClassFilesBuilder.addSchemaGRTReference(overriddenField.type.baseTypeDef)
        return overriddenField.kmType(pkg, baseTypeMapper)
    }

    private fun ViaductSchema.Field.covariantReturnTypeBridge(
        returnType: KmType,
        fetchExpr: String
    ): ReturnTypeBridge? {
        val bridgeReturnType = covariantOverrideBridgeReturnType(returnType)?.also {
            it.isNullable = returnType.isNullable
        } ?: return null
        if (bridgeReturnType.javaTypeName == returnType.javaTypeName) return null
        return ReturnTypeBridge(
            returnType = bridgeReturnType,
            body = returnBody(bridgeReturnType, fetchExpr)
        )
    }

    // Finds the nearest ancestor field whose JVM type differs from concreteType.
    // Using firstNotNullOfOrNull (stops at first match) would return Node.id (GlobalID<Node>)
    // before a non-node interface's id (String), making the bridge type comparison a no-op.
    private fun ViaductSchema.Object.findOverriddenFieldWithDifferentType(
        fieldName: String,
        concreteType: KmType
    ): ViaductSchema.Field? = supers.firstNotNullOfOrNull { it.findFieldWithDifferentTypeInHierarchy(fieldName, concreteType) }

    private fun ViaductSchema.Interface.findFieldWithDifferentTypeInHierarchy(
        fieldName: String,
        concreteType: KmType
    ): ViaductSchema.Field? {
        val f = field(fieldName)
        if (f != null && f.kmType(pkg, baseTypeMapper).javaTypeName != concreteType.javaTypeName) return f
        return supers.firstNotNullOfOrNull { it.findFieldWithDifferentTypeInHierarchy(fieldName, concreteType) }
    }

    private fun CustomClassBuilder.addOfObject(): CustomClassBuilder {
        addOfObject(cfg.EXECUTION_CONTEXT.asKmName.asType())
        return this
    }

    private fun CustomClassBuilder.addRootFieldReferences(): CustomClassBuilder {
        val fields = def.rootFieldReferenceFields(grtClassFilesBuilder.reverseSchema, grtClassFilesBuilder.schema.queryTypeDef)
        if (fields.isEmpty()) return this

        val companion = companionObjectBuilder()

        fields.forEach { field ->
            val argumentsClass = field.args.takeIf { it.isNotEmpty() }?.let { arguments ->
                nestedClassBuilder(JavaIdName(rootFieldReferenceArgumentsName(field))).also { argumentsReceiver ->
                    val argumentsBuilderType = KmName("$pkg/${cfg.argumentTypeName(field)}.Builder").asType()
                    argumentsReceiver.addStoredProperty("arguments", argumentsBuilderType)
                    argumentsReceiver.addPropertiesConstructor(listOf("arguments" to argumentsBuilderType))
                    arguments.forEach { arg -> argumentsReceiver.addArgumentSetter(arg) }
                }
            }
            val callClass = nestedClassBuilder(
                JavaIdName(rootFieldCallName(field)),
                kind = if (argumentsClass == null) ClassKind.OBJECT else ClassKind.CLASS,
            )
            callClass.addRootFieldCall(field, argumentsClass)
            companion.addRootFieldReferenceMethod(field, argumentsClass, callClass)
        }
        return this
    }

    private fun CustomClassBuilder.addStoredProperty(
        name: String,
        type: KmType,
    ) {
        addProperty(
            KmPropertyBuilder(
                JavaIdName(name),
                type,
                type,
                isVariable = false,
                constructorProperty = true,
            ).apply {
                getterVisibility(Visibility.PRIVATE)
                propertyModality(Modality.FINAL)
            }
        )
    }

    private fun CustomClassBuilder.addRootFieldReferenceMethod(
        field: ViaductSchema.Field,
        argumentsClass: CustomClassBuilder?,
        callClass: CustomClassBuilder,
    ) {
        val returnType = rootFieldCallType(field)
        val function = KmFunction(field.name).apply {
            visibility = Visibility.PUBLIC
            modality = Modality.FINAL
            this.returnType = returnType
            argumentsClass?.let { argumentsReceiver ->
                valueParameters += KmValueParameter("configure").also {
                    it.type = rootFieldReferenceConfigureType(argumentsReceiver)
                }
            }
        }

        addFunction(
            function,
            body = buildString {
                append("{\n")
                if (argumentsClass != null) {
                    append("return new ${callClass.kmName.asJavaName}($1);\n")
                } else {
                    append("return ${callClass.kmName.asJavaBinaryName}.INSTANCE;\n")
                }
                append("}")
            }
        )
    }

    private fun CustomClassBuilder.addRootFieldCall(
        field: ViaductSchema.Field,
        argumentsClass: CustomClassBuilder?,
    ) {
        val returnType = field.kmType(pkg, baseTypeMapper).also { it.isNullable = false }
        addSupertype(rootFieldCallType(field))

        argumentsClass?.let {
            val storedProperties = listOf("configure" to rootFieldReferenceConfigureType(it))
            storedProperties.forEach { (name, type) -> addStoredProperty(name, type) }
            addPropertiesConstructor(storedProperties)
        }

        addFunction(
            KmFunction("field").apply {
                visibility = Visibility.PUBLIC
                modality = Modality.FINAL
                this.returnType = rootObjectFieldType(returnType)
            },
            body = rootFieldCallFieldBody(field),
        )
        addFunction(
            KmFunction("arguments").apply {
                visibility = Visibility.PUBLIC
                modality = Modality.FINAL
                this.returnType = cfg.ARGUMENTS_GRT.asKmName.asType()
                valueParameters += KmValueParameter("context").also {
                    it.type = cfg.EXECUTION_CONTEXT.asKmName.asType()
                }
            },
            body = rootFieldCallArgumentsBody(field, argumentsClass),
        )
    }

    private fun CustomClassBuilder.addPropertiesConstructor(properties: List<Pair<String, KmType>>) {
        val constructor = KmConstructor().apply {
            visibility = Visibility.INTERNAL
            properties.forEach { (name, type) ->
                valueParameters += KmValueParameter(name).also { it.type = type }
            }
        }
        addConstructor(
            constructor,
            body = buildString {
                append("{\n")
                append(checkNotNullParameterExpressions(constructor.valueParameters))
                properties.forEachIndexed { index, (name, _) ->
                    append("this.$name = $${index + 1};\n")
                }
                append("}")
            }
        )
    }

    private fun rootFieldCallFieldBody(field: ViaductSchema.Field): String {
        val fieldsName = objectClass.kmName.append(".Fields").asJavaBinaryName
        return "{ return $fieldsName.INSTANCE.${getterName(field.name)}(); }"
    }

    private fun rootFieldCallArgumentsBody(
        field: ViaductSchema.Field,
        argumentsClass: CustomClassBuilder?,
    ): String =
        buildString {
            append("{\n")
            if (field.args.none()) {
                append("return viaduct.api.types.Arguments${'$'}NoArguments.INSTANCE;\n")
                append("}")
                return@buildString
            }

            val argumentsReceiver = checkNotNull(argumentsClass) {
                "Root field reference '${field.name}' has arguments but no arguments receiver class"
            }
            val argumentsBuilderType = KmName("$pkg/${cfg.argumentTypeName(field)}.Builder")
            append(
                "${argumentsBuilderType.asJavaName} arguments = new ${argumentsBuilderType.asJavaName}($1);\n"
            )
            append("this.configure.invoke(new ${argumentsReceiver.kmName.asJavaName}(arguments));\n")
            append("return arguments.build();\n")
            append("}")
        }

    private fun rootObjectFieldType(unwrappedType: KmType): KmType =
        cfg.REFLECTED_ROOT_OBJECT_FIELD.asKmName.asType().also {
            it.arguments += KmTypeProjection.STAR
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, unwrappedType)
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, cfg.ARGUMENTS_GRT.asKmName.asType())
        }

    private fun rootFieldCallType(field: ViaductSchema.Field): KmType =
        cfg.ROOT_FIELD_CALL.asKmName.asType().also {
            val returnType = field.kmType(pkg, baseTypeMapper).also { type -> type.isNullable = false }
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, returnType)
        }

    private fun rootFieldReferenceConfigureType(argumentsClass: CustomClassBuilder): KmType =
        Km.FUNCTION1.asType().also {
            it.annotations.add(kotlinx.metadata.KmAnnotation("kotlin/ExtensionFunctionType", emptyMap()))
            it.arguments += KmTypeProjection(KmVariance.IN, argumentsClass.kmType)
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, Km.UNIT.asType())
        }

    private fun CustomClassBuilder.addArgumentSetter(arg: ViaductSchema.FieldArg) {
        val argType = arg.kmType(pkg, baseTypeMapper, isInput = true)
        val function = KmFunction(arg.name).apply {
            visibility = Visibility.PUBLIC
            modality = Modality.FINAL
            returnType = this@addArgumentSetter.kmType
            valueParameters += KmValueParameter("value").also { it.type = argType }
        }
        addFunction(
            function,
            body = buildString {
                append("{\n")
                append("this.arguments.${arg.name}($1);\n")
                append("return this;\n")
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
                append("    this.get__context(),\n")
                append("    this.get__engineObject().getType(),\n")
                append("    this.toBuilderEOD()\n")
                append(");\n")
                append("}")
            }
        )
        return this
    }

    private fun rootFieldReferenceArgumentsName(field: ViaductSchema.Field): String = field.name.replaceFirstChar { it.uppercase() } + "Arguments"

    private fun rootFieldCallName(field: ViaductSchema.Field): String = field.name.replaceFirstChar { it.uppercase() } + "RootFieldCall"
}
