package viaduct.graphql.schema.graphqljava

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

class GRSchemaRawRootTypeTests : RootTypeFactoryContractForRaw {
    override fun makeSchema(schema: String): ViaductSchema = ViaductSchema.fromTypeDefinitionRegistry(readTypes(schema))
}
