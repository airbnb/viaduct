package viaduct.graphql.schema.graphqljava

import viaduct.graphql.schema.test.ViaductSchemaSubtypeContract

class GJSchemaRawViaductSchemaSubtypeContractTest : ViaductSchemaSubtypeContract() {
    override fun getSchemaClass() = GJSchemaRaw::class
}
