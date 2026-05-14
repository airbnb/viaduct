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
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.ct.javaTypeName
import viaduct.codegen.km.CustomClassBuilder
import viaduct.codegen.km.boxedJavaName
import viaduct.codegen.km.castObjectExpression
import viaduct.codegen.km.checkNotNullParameterExpression
import viaduct.codegen.km.getterName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.baseTypeKmType
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.codegenIncludedFields
import viaduct.tenant.codegen.bytecode.config.connectionEdgeTypeName
import viaduct.tenant.codegen.bytecode.config.hasConnectionDirective
import viaduct.tenant.codegen.bytecode.config.hasEdgeDirective
import viaduct.tenant.codegen.bytecode.config.isNode
import viaduct.tenant.codegen.bytecode.config.kmType
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
        for (field in def.codegenIncludedFields) {
            // Generate two field getters per field.
            // One to handle alias param and fetch the field value.
            this.addFieldGetter(field, orNull = false)
            // The other is just to pass default null as alias and
            // invoke the first getter.
            this.addFieldGetterToPassDefaultValue(field, orNull = false)

            // Emit OrNull variants that swallow exceptions and return null.
            this.addFieldGetter(field, orNull = true)
            this.addFieldGetterToPassDefaultValue(field, orNull = true)
        }
        return this
    }

    private fun CustomClassBuilder.addFieldGetter(
        field: ViaductSchema.Field,
        orNull: Boolean
    ) {
        grtClassFilesBuilder.addSchemaGRTReference(field.type.baseTypeDef)

        val fetchMethod = if (orNull) "getOrNullInternal" else "getInternal"
        val getterSuffix = if (orNull) "OrNull" else ""
        val methodName = getterName(field.name) + getterSuffix

        val kmFun = KmFunction(methodName).apply {
            visibility = Visibility.PUBLIC
            modality = Modality.FINAL
            isSuspend = false
            returnType = field.kmType(pkg, baseTypeMapper).also { t ->
                if (orNull) t.isNullable = true
            }
            valueParameters.add(
                KmValueParameter("alias").apply {
                    type = Km.STRING.asNullableType()
                }
            )
        }

        val returnType = field.kmType(pkg, baseTypeMapper).also { t ->
            if (orNull) t.isNullable = true
        }
        val fetchExpr = field.fetchExpression("$1", fetchMethod)
        val bridge = field.covariantReturnTypeBridge(returnType, fetchExpr)
        this.addFunctionWithReturnTypeBridge(
            kmFun,
            body = returnBody(returnType, fetchExpr),
            bridge = bridge,
        )
    }

    private fun CustomClassBuilder.addFieldGetterToPassDefaultValue(
        field: ViaductSchema.Field,
        orNull: Boolean
    ) {
        grtClassFilesBuilder.addSchemaGRTReference(field.type.baseTypeDef)

        val methodName = if (orNull) "${getterName(field.name)}OrNull" else getterName(field.name)

        val kmFun = KmFunction(methodName).also {
            it.visibility = Visibility.PUBLIC
            it.modality = Modality.FINAL
            it.isSuspend = false
            it.returnType = field.kmType(pkg, baseTypeMapper).also { t ->
                if (orNull) t.isNullable = true
            }
        }

        val fetchMethod = if (orNull) "getOrNullInternal" else "getInternal"
        val bridge = field.covariantReturnTypeBridge(kmFun.returnType, field.fetchExpression("(String)null", fetchMethod))
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
        // Connection types require a richer context carrying edge/node/argument type info;
        // plain object types use the standard ExecutionContext.
        val contextType = if (def.hasConnectionDirective) {
            cfg.CONNECTION_FIELD_EXECUTION_CONTEXT.asKmName.asType().also {
                it.arguments += KmTypeProjection.STAR
                it.arguments += KmTypeProjection.STAR
                it.arguments += KmTypeProjection(KmVariance.OUT, cfg.CONNECTION_ARGUMENTS.asKmName.asType())
                it.arguments += KmTypeProjection(KmVariance.INVARIANT, this.kmType)
            }
        } else {
            cfg.EXECUTION_CONTEXT.asKmName.asType()
        }
        addOfObject(contextType)
        return this
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
}
