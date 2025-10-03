package viaduct.service.runtime

import com.google.inject.AbstractModule

/**
 * Module that binds a specific SchemaRegistryConfiguration instance.
 * This module is used in child injectors to provide the schema configuration
 * that providers can inject to create schema-specific components.
 */
class SchemaConfigurationModule(
    private val schemaConfig: SchemaRegistryConfiguration
) : AbstractModule() {
    override fun configure() {
        // Bind the specific configuration instance for this child injector
        bind(SchemaRegistryConfiguration::class.java)
            .toInstance(schemaConfig)
    }
}
