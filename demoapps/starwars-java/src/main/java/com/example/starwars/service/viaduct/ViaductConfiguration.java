package com.example.starwars.service.viaduct;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import java.util.Set;
import viaduct.service.SchemaScopeInfo;
import viaduct.service.ViaductBuilder;
import viaduct.service.api.Viaduct;
import viaduct.service.api.spi.FlagManager;

/** Registers the public and extras schema variants. */
@Factory
public final class ViaductConfiguration {
  public static final String DEFAULT_SCOPE_ID = "default";
  public static final String EXTRAS_SCOPE_ID = "extras";
  public static final SchemaScopeInfo DEFAULT_SCHEMA =
      SchemaScopeInfo.fromResources(Set.of("publicSchema")).get(0);
  public static final SchemaScopeInfo EXTRAS_SCHEMA =
      SchemaScopeInfo.fromResources(Set.of("publicSchemaWithExtras")).get(0);

  private final MicronautTenantModuleInjectorFactory tenantModuleInjectorFactory;

  public ViaductConfiguration(MicronautTenantModuleInjectorFactory tenantModuleInjectorFactory) {
    this.tenantModuleInjectorFactory = tenantModuleInjectorFactory;
  }

  @Bean
  public Viaduct providesViaduct() {
    return new ViaductBuilder()
        .withTenantModuleInjectorFactory(tenantModuleInjectorFactory)
        .withScopedSchemas(SchemaScopeInfo.fromResources())
        .withFlagManager(
            flag ->
                flag == FlagManager.Flags.ENGINE2_ENABLED
                    || FlagManager.Default.INSTANCE.isEnabled(flag))
        .build();
  }
}
