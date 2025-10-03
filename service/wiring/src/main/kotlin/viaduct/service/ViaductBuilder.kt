package viaduct.service

import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.runtime.SchemaRegistryConfiguration
import viaduct.service.runtime.StandardViaduct

class ViaductBuilder {
    val builder = StandardViaduct.Builder()

    /** See [withTenantAPIBootstrapperBuilder]. */
    fun withTenantAPIBootstrapperBuilder(builder: TenantAPIBootstrapperBuilder) =
        apply {
            this.builder.withTenantAPIBootstrapperBuilders(listOf(builder))
        }

    /**
     * Adds a TenantAPIBootstrapperBuilder to be used for creating TenantAPIBootstrapper instances.
     * Multiple builders can be added, and all their TenantAPIBootstrapper instances will be used
     * together to bootstrap tenant modules.
     *
     * @param builders The builder instance that will be used to create a TenantAPIBootstrapper
     * @return This Builder instance for method chaining
     */
    fun withTenantAPIBootstrapperBuilders(builders: List<TenantAPIBootstrapperBuilder>) =
        apply {
            builder.withTenantAPIBootstrapperBuilders(builders)
        }

    /**
     * A convenience function to indicate that no bootstrapper is
     * wanted.  Used for testing purposes.
     * Failing to provide a bootstrapper is an error that should be flagged at build() time.
     */
    fun withNoTenantAPIBootstrapper() =
        apply {
            builder.withTenantAPIBootstrapperBuilders(emptyList())
        }

    fun withFlagManager(flagManager: FlagManager) =
        apply {
            builder.withFlagManager(flagManager)
        }

    /**
     * By default, Viaduct instances implement `Query.node` and `Query.nodes`
     * resolvers automatically.  Calling this function with false turns off that default behavior.
     * (If your schema does not have the `Query.node/s` field(s), you do
     * _not_ have to explicitly turn off the default behavior.)
     */
    fun standardNodeBehavior(standardNodeBehavior: Boolean) =
        apply {
            builder.withoutDefaultQueryNodeResolvers(standardNodeBehavior)
        }

    fun withSchemaRegistryConfiguration(schemaRegistryConfiguration: SchemaRegistryConfiguration) =
        apply {
            builder.withSchemaRegistryConfiguration(schemaRegistryConfiguration)
        }

    /**
     * Builds the Guice Module within Viaduct and gets Viaduct from the injector.
     *
     * @return a Viaduct Instance ready to execute
     */
    fun build(): StandardViaduct = builder.build()
}
