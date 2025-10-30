package viaduct.graphql.schema

import viaduct.graphql.schema.test.ViaductSchemaSubtypeContract

class FilteredSchemaViaductExtendedSubtypeContractTest : ViaductSchemaSubtypeContract() {
    override fun getSchemaClass() = FilteredSchema::class

    override val skipExtensionTests = true
}
