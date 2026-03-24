package viaduct.graphql.schema.graphqljava

import graphql.schema.GraphQLSchema
import viaduct.graphql.schema.ViaductSchema

// todo(diwas): remove this class eventually
// This class is added temporarily here to facilitate OSS lib
// migration from GraphQLSchema to ViaductSchema
fun GraphQLSchema.viaductSchema(): ViaductSchema = gjSchemaFromSchema(this)
