package viaduct.graphql.schema.binary

import viaduct.graphql.schema.test.ViaductSchemaSubtypeContract

/**
 * Validates that BSchema's type hierarchy is correctly structured using
 * Kotlin reflection. This ensures all return types are properly typed
 * (e.g., Field.containingDef returns BSchema.Record, not just
 * ViaductSchema.Record).
 */
internal class BSchemaSubtypeContractTest : ViaductSchemaSubtypeContract() {
    override fun getSchemaClass() = BSchema::class
}
