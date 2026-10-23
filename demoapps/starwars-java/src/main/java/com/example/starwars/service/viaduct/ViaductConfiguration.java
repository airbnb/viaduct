package com.example.starwars.service.viaduct;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.util.Set;
import viaduct.service.SchemaScopeInfo;
import viaduct.service.ViaductBuilder;
import viaduct.service.api.Viaduct;
import viaduct.service.api.spi.FlagManager;

/** Registers the public and extras schema variants. */
// tag::viaduct_configuration
@Factory
public final class ViaductConfiguration {
  public record StarWarsSchemas(SchemaScopeInfo defaultSchema, SchemaScopeInfo extras) {}

  private final MicronautTenantModuleInjectorFactory tenantModuleInjectorFactory;

  public ViaductConfiguration(MicronautTenantModuleInjectorFactory tenantModuleInjectorFactory) {
    this.tenantModuleInjectorFactory = tenantModuleInjectorFactory;
  }

  // tag::declared_schemas
  @Singleton
  public StarWarsSchemas providesStarWarsSchemas() {
    return new StarWarsSchemas(
        SchemaScopeInfo.fromResources(Set.of("publicSchema")).get(0),
        SchemaScopeInfo.fromResources(Set.of("publicSchemaWithExtras")).get(0));
  }

  // end::declared_schemas

  @Bean
  public Viaduct providesViaduct() {
    // tag::schema_registration
    return new ViaductBuilder()
        .withTenantModuleInjectorFactory(tenantModuleInjectorFactory)
        .withScopedSchemas(SchemaScopeInfo.fromResources())
        .withFlagManager(
            flag ->
                flag == FlagManager.Flags.ENGINE2_ENABLED
                    || FlagManager.Default.INSTANCE.isEnabled(flag))
        .build();
    // end::schema_registration
  }
}
// end::viaduct_configuration
