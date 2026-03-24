package viaduct.graphql.schema.binary

import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.test.ViaductSchemaSubtypeContract

/**
 * Validates that SchemaWithData's type hierarchy is correctly structured using
 * Kotlin reflection. This ensures all return types are properly typed
 * (e.g., Field.containingDef returns SchemaWithData.Record, not just
 * ViaductSchema.Record).
 */
internal class BSchemaSubtypeContractTest : ViaductSchemaSubtypeContract() {
    override fun getSchemaClass() = SchemaWithData::class
}
