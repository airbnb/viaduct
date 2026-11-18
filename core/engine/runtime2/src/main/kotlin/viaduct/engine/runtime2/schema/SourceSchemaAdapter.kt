package viaduct.engine.runtime2.schema

import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.schema.lowering.loweredFieldFromSourceCoordinate
import viaduct.engine.runtime2.schema.lowering.sourceTypeExpr
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.gjDef

/** Adapts source coordinates and reference paths using a canonical lowered schema's source definitions. */
class SourceSchemaAdapter(
    private val schema: ViaductSchema,
) {
    fun field(
        typeName: String,
        fieldName: String,
    ): ViaductSchema.Field = schema.loweredFieldFromSourceCoordinate(typeName, fieldName)

    fun typeExpr(field: ViaductSchema.Field): ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> = schema.sourceTypeExpr(field)

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
}

private fun GraphQLOutputType.unwrapNonNull(): GraphQLOutputType =
    if (this is GraphQLNonNull) {
        wrappedType as GraphQLOutputType
    } else {
        this
    }
