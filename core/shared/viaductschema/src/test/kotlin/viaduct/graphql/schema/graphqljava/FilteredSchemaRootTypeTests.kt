package viaduct.graphql.schema.graphqljava

import graphql.schema.idl.UnExecutableSchemaGenerator
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema

class FilteredSchemaRootTypeTests : RootTypeFactoryContractForBoth {
    override fun makeSchema(schema: String): ViaductSchema {
        val registry = readTypes(schema)
        val graphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
        return ViaductSchema.fromGraphQLSchema(graphQLSchema).filter(NoopSchemaFilter())
    }
}
