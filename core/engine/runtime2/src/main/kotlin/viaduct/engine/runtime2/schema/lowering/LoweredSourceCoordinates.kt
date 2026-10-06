package viaduct.engine.runtime2.schema.lowering

import viaduct.engine.runtime2.model.requireOutputType
import viaduct.graphql.schema.ViaductSchema

/**
 * Resolves one source GraphQL field coordinate to its canonical lowered field.
 *
 * Source fields retain their coordinates. Typename is a source pseudo-field represented by an
 * ordinary synthetic field after lowering.
 */
fun ViaductSchema.loweredFieldFromSourceCoordinate(
    sourceTypeName: String,
    sourceFieldName: String,
): ViaductSchema.Field {
    requireSourceName(sourceTypeName, sourceFieldName)
    if (sourceFieldName == "__typename") {
        return loweredTypenameField(sourceTypeName)
    }

    val owner =
        types[sourceTypeName] as? ViaductSchema.OutputRecord
            ?: throw invalidSourceField(sourceTypeName, sourceFieldName)
    return owner.field(sourceFieldName)
        ?: throw invalidSourceField(sourceTypeName, sourceFieldName)
}

/** Resolves the ordinary lowered field representing source `__typename` demand. */
internal fun ViaductSchema.loweredTypenameField(sourceTypeName: String): ViaductSchema.Field {
    requireSourceName(sourceTypeName, "__typename")
    val sourceType =
        types[sourceTypeName] as? ViaductSchema.CompositeTypeDef
            ?: throw invalidSourceField(sourceTypeName, "__typename")
    val ownerName =
        if (sourceType is ViaductSchema.Union) {
            ALL_SOURCE_OBJECTS_TYPE
        } else {
            sourceTypeName
        }
    val owner =
        types[ownerName] as? ViaductSchema.OutputRecord
            ?: throw invalidSourceField(sourceTypeName, "__typename")
    return owner.field(LOWERED_TYPENAME_FIELD)
        ?: throw invalidSourceField(sourceTypeName, "__typename")
}

/**
 * Returns the source output type represented by this canonical lowered field.
 */
internal fun ViaductSchema.sourceTypeExpr(field: ViaductSchema.Field): ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> = field.type.requireOutputType()

private fun requireSourceName(
    sourceTypeName: String,
    sourceFieldName: String,
) {
    if (
        sourceTypeName.contains(LOWERING_SYNTHETIC_NAME_TOKEN) ||
        sourceFieldName.contains(LOWERING_SYNTHETIC_NAME_TOKEN)
    ) {
        throw invalidSourceField(sourceTypeName, sourceFieldName)
    }
}

private fun invalidSourceField(
    sourceTypeName: String,
    sourceFieldName: String,
): IllegalArgumentException =
    IllegalArgumentException(
        "$sourceTypeName/$sourceFieldName is not a source GraphQL field",
    )
