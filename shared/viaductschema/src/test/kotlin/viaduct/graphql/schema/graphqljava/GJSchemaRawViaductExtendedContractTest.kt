package viaduct.graphql.schema.graphqljava

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.test.ViaductSchemaContract

class GJSchemaRawViaductExtendedContractTest : ViaductSchemaContract {
    override fun makeSchema(schema: String): ViaductSchema = GJSchemaRaw.fromRegistry(readTypes(schema))
}
