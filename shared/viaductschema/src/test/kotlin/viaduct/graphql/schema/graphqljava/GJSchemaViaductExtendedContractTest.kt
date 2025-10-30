package viaduct.graphql.schema.graphqljava

import viaduct.graphql.schema.test.ViaductSchemaContract

class GJSchemaViaductExtendedContractTest : ViaductSchemaContract {
    override fun makeSchema(schema: String): GJSchema = GJSchema.fromRegistry(readTypes(schema))
}
