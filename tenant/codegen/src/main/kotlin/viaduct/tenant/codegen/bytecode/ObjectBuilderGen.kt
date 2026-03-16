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
import viaduct.codegen.utils.KmName
import viaduct.codegen.utils.name
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.codegenIncludedFields
import viaduct.tenant.codegen.bytecode.config.connectionEdgeTypeName
import viaduct.tenant.codegen.bytecode.config.hasConnectionDirective
import viaduct.tenant.codegen.bytecode.config.kmType
import viaduct.tenant.codegen.bytecode.config.typeOfNodeField

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

/**
 * Generates a Builder class for GraphQL object types.
 *
 * For Connection types (types with @connection directive), generates a builder that extends
 * ConnectionBuilder<C, E, N> with specialized context handling. Note that N is inferred from E : Edge<N>.
 *
 * For regular object types, generates a builder that extends ObjectBase.Builder<T>.
 */
private class ObjectBuilderGenV2(
    private val grtClassFilesBuilder: GRTClassFilesBuilderBase,
    private val def: ViaductSchema.Object,
    private val builderClass: CustomClassBuilder,
    private val builderFor: KmType
) {
    private val pkg = grtClassFilesBuilder.pkg
    private val baseTypeMapper = grtClassFilesBuilder.baseTypeMapper

    /** Connection type metadata, null if this is a regular object type. */
    private val connectionInfo: ConnectionInfo? = resolveConnectionInfo()

    init {
        addSupertype()

        builderClass.addPrimaryConstructor()
        builderClass.addSecondaryConstructorForToBuilder()
        builderClass.addFieldSetters()
        builderClass.addBuildFun()
    }

    private fun resolveConnectionInfo(): ConnectionInfo? {
        if (!def.hasConnectionDirective) return null

        val edgeTypeName = checkNotNull(def.connectionEdgeTypeName) {
            "Connection type '${def.name}' has @connection directive but no edge type"
        }
        val edgeType = grtClassFilesBuilder.schema.types[edgeTypeName] as? ViaductSchema.Object
            ?: error("Connection type '${def.name}' edge type '$edgeTypeName' is not an Object type")
        val nodeTypeName = checkNotNull(edgeType.typeOfNodeField) {
            "Edge type '$edgeTypeName' for connection '${def.name}' has no 'node' field"
        }

        return ConnectionInfo(
            edgeTypeName = edgeTypeName,
            edgeKmType = KmName("$pkg/$edgeTypeName").asType(),
            nodeKmType = KmName("$pkg/$nodeTypeName").asType()
        )
    }

    private fun addSupertype() {
        // ConnectionBuilder<C, E, N>
        val supertype = if (connectionInfo != null) {
            cfg.CONNECTION_BUILDER.asKmName.asType().also {
                it.arguments += KmTypeProjection(KmVariance.INVARIANT, builderFor)
                it.arguments += KmTypeProjection(KmVariance.INVARIANT, connectionInfo.edgeKmType)
                it.arguments += KmTypeProjection(KmVariance.INVARIANT, connectionInfo.nodeKmType)
            }
        } else {
            cfg.OBJECT_BASE_BUILDER.asKmName.asType().also {
                it.arguments += KmTypeProjection(KmVariance.INVARIANT, builderFor)
            }
        }
        builderClass.addSupertype(supertype)
    }

    private fun CustomClassBuilder.addPrimaryConstructor(): CustomClassBuilder {
        val contextType = connectionInfo?.contextType ?: cfg.EXECUTION_CONTEXT.asKmName.asType()

        val kmConstructor = KmConstructor().also { constructor ->
            constructor.visibility = Visibility.PUBLIC
            constructor.hasAnnotations = false
            constructor.isSecondary = true
            constructor.valueParameters.add(
                KmValueParameter("context").also { it.type = contextType }
            )
        }

        addConstructor(
            kmConstructor,
            superCall = buildSuperCall(),
            body = "{\n${checkNotNullParameterExpression(contextType, 1, "context")}}"
        )
        return this
    }

    private fun buildSuperCall(): String {
        val contextCast = castObjectExpression(cfg.INTERNAL_CONTEXT.asKmName.asType(), "$1")
        val schemaLookup = "($contextCast).getSchema().getSchema().getObjectType(\"${def.name}\")"

        return if (connectionInfo != null) {
            val edgeReflectionClass = KmName("$pkg/${connectionInfo.edgeTypeName}.${cfg.REFLECTION_NAME}").asJavaBinaryName
            // Cast to cfg.REFLECTED_TYPE (viaduct.api.reflect.Type) so Javassist's type-checker can match the
            // ConnectionBuilder constructor's Type<E> parameter. Without the cast, Javassist infers the type
            // as the placeholder class (ExampleEdge$Reflection) which doesn't declare the interface, causing
            // "cannot find constructor" when the edge type is in a different build shard.
            "super($1, $schemaLookup, null, (${cfg.REFLECTED_TYPE}) $edgeReflectionClass.INSTANCE);"
        } else {
            "super($contextCast, $schemaLookup, null);"
        }
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
                    KmValueParameter("type").also {
                        it.type = cfg.GRAPHQL_OBJECT_TYPE.asKmName.asType()
                    },
                    KmValueParameter("baseEngineObjectData").also {
                        it.type = cfg.ENGINE_OBJECT_DATA.asKmName.asType()
                    },
                )
            )
        }

        val superCall = if (connectionInfo != null) {
            val contextCast = castObjectExpression(cfg.CONNECTION_FIELD_EXECUTION_CONTEXT.asKmName.asType(), "$1")
            val edgeReflectionClass = KmName("$pkg/${connectionInfo.edgeTypeName}.${cfg.REFLECTION_NAME}").asJavaBinaryName
            "super($contextCast, $2, $3, (${cfg.REFLECTED_TYPE}) $edgeReflectionClass.INSTANCE);"
        } else {
            "super($1, $2, $3);"
        }

        this.addConstructor(
            kmConstructor,
            superCall = superCall,
            body = buildString {
                append("{\n")
                append(checkNotNullParameterExpression(cfg.INTERNAL_CONTEXT.asKmName.asType(), 1, "context"))
                append(checkNotNullParameterExpression(cfg.GRAPHQL_OBJECT_TYPE.asKmName.asType(), 2, "type"))
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
            it.valueParameters.add(
                KmValueParameter("value").also { param -> param.type = fieldKmType }
            )
        }

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

    /**
     * Holds resolved type information for Connection types.
     */
    private inner class ConnectionInfo(
        val edgeTypeName: String,
        val edgeKmType: KmType,
        val nodeKmType: KmType
    ) {
        /** ConnectionFieldExecutionContext<*, *, in ConnectionArguments, C> for the primary constructor. */
        val contextType: KmType = cfg.CONNECTION_FIELD_EXECUTION_CONTEXT.asKmName.asType().also {
            it.arguments += KmTypeProjection.STAR // T (Object)
            it.arguments += KmTypeProjection.STAR // Q (Query)
            it.arguments += KmTypeProjection(KmVariance.OUT, cfg.CONNECTION_ARGUMENTS.asKmName.asType()) // A (ConnectionArguments)
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, builderFor) // C (Connection type)
        }
    }
}
