package model

import model.testing.GJSchema
import viaduct.graphql.schema.ViaductSchema

/**
 * Explicit source-to-canonical adapter for fixture composition boundaries.
 *
 * Semantic code and tests should use [ViaductSchema.requireField] and [ViaductSchema.requireObjectField] with canonical model
 * coordinates. This adapter is reserved for inputs that are intentionally expressed in the
 * retained GraphQL source schema.
 */
class SourceSchemaAdapter(
    schema: ViaductSchema,
) {
    private val schema =
        requireNotNull(schema as? GJSchema) {
            "SourceSchemaAdapter requires the canonical source/lowered fixture schema pair"
        }

    /** Resolves a source GraphQL field to its canonical lowered fixture coordinate. */
    fun field(
        typeName: String,
        fieldName: String,
    ): ViaductSchema.Field = schema.fieldFromSource(typeName, fieldName)

    /** Returns the source GraphQL output type represented by a canonical fixture field. */
    fun typeExpr(field: ViaductSchema.Field): ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> = schema.sourceTypeExpr(field)

    /** Lowers a source-shaped output for storage at a canonical fixture field. */
    fun lowerOutput(
        field: ViaductSchema.Field,
        output: ResolverOutputData?,
    ): ResolverOutputData? = schema.lowerSourceOutput(field, output)

    /** Normalizes the materialized result of a concrete node resolver without making another reference. */
    fun lowerNodeResolverOutput(
        type: ViaductSchema.Object,
        output: ResolverOutputData?,
    ): ResolverOutputData? = schema.lowerNodeResolverOutput(type, output)

    /** Converts one production source-schema root-field reference to the canonical qplan carrier. */
    fun lowerRootFieldReference(
        rootFieldPath: List<String>,
        sourceTypeName: String,
        arguments: Map<String, Any?>,
    ): RootFieldReferenceData = schema.lowerRootFieldReference(rootFieldPath, sourceTypeName, arguments)
}
