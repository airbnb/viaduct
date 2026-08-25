package viaduct.graphql.schema.graphqljava

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.test.ViaductSchemaContract

class GJSchemaRawViaductExtendedContractTest : ViaductSchemaContract {
    override fun makeSchema(schema: String): ViaductSchema = ViaductSchema.fromTypeDefinitionRegistry(readTypes(schema))
}
