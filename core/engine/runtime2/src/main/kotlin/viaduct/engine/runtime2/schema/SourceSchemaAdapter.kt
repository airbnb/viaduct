package viaduct.engine.runtime2.schema

import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineObjectDataEntry
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.nodeRootFieldReferenceOf
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.qplanSchemaTypeOrNull
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.schema.lowering.loweredFieldFromSourceCoordinate
import viaduct.engine.runtime2.schema.lowering.sourceTypeExpr
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.gjDef
import viaduct.graphql.schema.isNode

/** Adapts source coordinates and outputs using a canonical lowered schema's source definitions. */
class SourceSchemaAdapter(
    private val schema: ViaductSchema,
) {
    fun field(
        typeName: String,
        fieldName: String,
    ): ViaductSchema.Field = schema.loweredFieldFromSourceCoordinate(typeName, fieldName)

    fun typeExpr(field: ViaductSchema.Field): ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> = schema.sourceTypeExpr(field)

    private fun isLoweredNodeField(field: ViaductSchema.Field): Boolean = typeExpr(field).baseTypeDef.isNode

    fun lowerOutput(
        field: ViaductSchema.Field,
        output: ResolverOutputData?,
    ): ResolverOutputData? {
        val sourceTypeExpr = typeExpr(field)
        return if (isLoweredNodeField(field)) {
            lowerNodeReferences(
                output = output,
                sourceTypeExpr = sourceTypeExpr,
            )
        } else {
            lowerOrdinaryOutput(
                output = output,
                sourceTypeExpr = sourceTypeExpr,
                loweredTypeExpr = field.outputType,
            )
        }
    }

    fun lowerNodeResolverOutput(
        type: ViaductSchema.Object,
        output: ResolverOutputData?,
    ): ResolverOutputData? {
        if (output == null || output is EngineErrorData || output is RootFieldReferenceData) {
            return output
        }
        require(output is EngineObjectData.Sync) {
            "Node resolver for ${type.name} returned a non-object value"
        }
        return lowerOrdinaryObject(
            output,
            ViaductSchema.TypeExpr(type),
        )
    }

    fun lowerRootFieldReference(
        rootFieldPath: List<String>,
        sourceTypeName: String,
        arguments: Map<String, Any?>,
    ): RootFieldReferenceData {
        require(rootFieldPath.isNotEmpty()) { "Root-field-reference path must not be empty" }
        var sourceParent = schema.requireQueryTypeDef().gjDef
        val canonicalPath =
            rootFieldPath.mapIndexed { index, fieldName ->
                val sourceField =
                    requireNotNull(sourceParent.getFieldDefinition(fieldName)) {
                        "Root-field-reference path has no field ${sourceParent.name}/$fieldName"
                    }
                val sourceOutput = sourceField.type.unwrapNonNull()
                require(sourceOutput !is GraphQLList && sourceOutput is GraphQLObjectType) {
                    "Root-field-reference path field ${sourceParent.name}/$fieldName " +
                        "must return a singular object"
                }
                val canonicalField = field(sourceParent.name, fieldName)
                require(canonicalField is ViaductSchema.ObjectField) {
                    "Root-field-reference path field ${sourceParent.name}/$fieldName " +
                        "does not lower to an object field"
                }
                if (index == rootFieldPath.lastIndex) {
                    require(sourceOutput.name == sourceTypeName) {
                        "Root-field-reference type $sourceTypeName does not match " +
                            "${sourceParent.name}/$fieldName type ${sourceOutput.name}"
                    }
                } else {
                    sourceParent = sourceOutput
                }
                canonicalField
            }
        return RootFieldReferenceData.of(canonicalPath, arguments)
    }

    private fun lowerNodeReferences(
        output: ResolverOutputData?,
        sourceTypeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    ): ResolverOutputData? =
        when {
            output == null || output is EngineErrorData || output is RootFieldReferenceData -> output
            sourceTypeExpr.isList -> {
                require(output is List<*>) {
                    "Node-list field resolver did not return a list"
                }
                val sourceElementType = checkNotNull(sourceTypeExpr.unwrapList())
                output.map { value ->
                    lowerNodeReferences(
                        output = value,
                        sourceTypeExpr = sourceElementType,
                    )
                }
            }
            else -> {
                require(output is EngineObjectData.Sync) {
                    "Node field resolver did not return a node reference"
                }
                val outputType =
                    schema.requireType(output.type.name) as? ViaductSchema.Object
                        ?: throw IllegalArgumentException(
                            "Node field resolver returned unknown object type ${output.type.name}",
                        )
                val idField = schema.requireObjectField(outputType.name, "id")
                val id = output.get(idField.name)
                require(id !is EngineErrorData && id is String) {
                    "Node reference ${outputType.name}/id must contain a non-error ID"
                }
                val declaredType = sourceTypeExpr.baseTypeDef as ViaductSchema.CompositeTypeDef
                require(outputType in declaredType.possibleObjectTypes) {
                    "Node reference ${outputType.name} is not valid for " +
                        sourceTypeExpr.baseTypeDef.name
                }
                nodeRootFieldReferenceOf(
                    queryNode = schema.requireObjectField("Query", "node"),
                    type = outputType,
                    id = id,
                )
            }
        }

    private fun lowerOrdinaryOutput(
        output: ResolverOutputData?,
        sourceTypeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        loweredTypeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    ): ResolverOutputData? =
        when {
            output == null || output is EngineErrorData || output is RootFieldReferenceData -> output
            sourceTypeExpr.isList && loweredTypeExpr.isList -> {
                require(output is List<*>) {
                    "Source output for $sourceTypeExpr is not a list"
                }
                val sourceElementType = checkNotNull(sourceTypeExpr.unwrapList())
                val loweredElementType = checkNotNull(loweredTypeExpr.unwrapList())
                output.map { value ->
                    lowerOrdinaryOutput(
                        output = value,
                        sourceTypeExpr = sourceElementType,
                        loweredTypeExpr = loweredElementType,
                    )
                }
            }
            !sourceTypeExpr.isList && !loweredTypeExpr.isList -> {
                val sourceType = sourceTypeExpr.baseTypeDef
                if (sourceType !is ViaductSchema.CompositeTypeDef) {
                    output
                } else {
                    require(output is EngineObjectData.Sync) {
                        "Source output for ${sourceType.name} is not an object"
                    }
                    lowerOrdinaryObject(output, loweredTypeExpr)
                }
            }
            else -> error("Source and lowered type expressions have different list shapes")
        }

    private fun lowerOrdinaryObject(
        output: EngineObjectData.Sync,
        loweredTypeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    ): EngineObjectData.Sync {
        output.qplanSchemaTypeOrNull?.let { return output }

        val outputType =
            schema.requireType(output.type.name) as? ViaductSchema.Object
                ?: throw IllegalArgumentException(
                    "Source resolver returned unknown object type ${output.type.name}",
                )
        val declaredType = loweredTypeExpr.baseTypeDef as ViaductSchema.CompositeTypeDef
        require(outputType in declaredType.possibleObjectTypes) {
            "Source object ${outputType.name} is not valid for ${declaredType.name}"
        }
        val sourceObject = outputType.gjDef
        val fields =
            output.getSelections().map { selection ->
                requireNotNull(sourceObject.getFieldDefinition(selection)) {
                    "Source object ${outputType.name} has no field named $selection"
                }
                val loweredField = field(outputType.name, selection)
                require(loweredField is ViaductSchema.ObjectField) {
                    "${outputType.name}/$selection does not lower to an object field"
                }
                require(loweredField.args.isEmpty()) {
                    "Passive object field ${outputType.name}/$selection must be argumentless"
                }
                EngineObjectDataEntry.of(
                    selection = loweredField.name,
                    field = loweredField,
                    value = lowerOutput(loweredField, output.get(selection)),
                )
            }
        return engineObjectDataOf(outputType, fields)
    }
}

private fun GraphQLOutputType.unwrapNonNull(): GraphQLOutputType =
    if (this is GraphQLNonNull) {
        wrappedType as GraphQLOutputType
    } else {
        this
    }
