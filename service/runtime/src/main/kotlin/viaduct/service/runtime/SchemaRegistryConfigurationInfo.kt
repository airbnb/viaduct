package viaduct.service.runtime

import viaduct.engine.api.ViaductSchema

/**
 * Immutable configuration for creating ViaductSchemaRegistry instances.
 * This is the output of SchemaRegistryConfiguration and input to ViaductSchemaRegistry.Factory.
 */
data class SchemaRegistryConfigurationInfo(
    val graphQLSchemaFactory: GraphQLSchemaFactory,
    val publicSchemaRegistration: List<PublicSchemaRegistration>,
    val asyncSchemaRegistration: Map<String, SchemaRegistryConfiguration.AsyncScopedSchema>,
    val fullSchema: ViaductSchema?
)
