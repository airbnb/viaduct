package viaduct.graphql.schema.graphqljava

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.test.ViaductSchemaContract

class FilteredSchemaViaductExtendedContractTest : ViaductSchemaContract {
    override fun makeSchema(schema: String): ViaductSchema = GJSchema.fromRegistry(readTypes(schema)).filter(NoopSchemaFilter())
}
