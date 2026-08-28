package com.example.starwars.service.viaduct;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import java.util.List;
import java.util.Set;
import viaduct.service.BasicViaductFactory;
import viaduct.service.SchemaScopeInfo;
import viaduct.service.api.Viaduct;

/** Registers the public and extras schema variants. */
@Factory
public final class ViaductConfiguration {
  public static final String DEFAULT_SCOPE_ID = "default";
  public static final String EXTRAS_SCOPE_ID = "extras";
  public static final SchemaScopeInfo.Scoped DEFAULT_SCHEMA =
      new SchemaScopeInfo.Scoped("publicSchema", Set.of(DEFAULT_SCOPE_ID));
  public static final SchemaScopeInfo.Scoped EXTRAS_SCHEMA =
      new SchemaScopeInfo.Scoped(
          "publicSchemaWithExtras", Set.of(DEFAULT_SCOPE_ID, EXTRAS_SCOPE_ID));

  private final MicronautTenantModuleInjectorFactory tenantModuleInjectorFactory;

  public ViaductConfiguration(MicronautTenantModuleInjectorFactory tenantModuleInjectorFactory) {
    this.tenantModuleInjectorFactory = tenantModuleInjectorFactory;
  }

  @Bean
  public Viaduct providesViaduct() {
    return BasicViaductFactory.create(
        tenantModuleInjectorFactory, List.of(DEFAULT_SCHEMA, EXTRAS_SCHEMA));
  }
}
