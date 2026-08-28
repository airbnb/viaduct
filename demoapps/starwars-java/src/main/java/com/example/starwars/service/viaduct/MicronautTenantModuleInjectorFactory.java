package com.example.starwars.service.viaduct;

import io.micronaut.context.BeanContext;
import jakarta.inject.Singleton;
import javax.inject.Provider;
import viaduct.service.api.spi.CodeInjector;
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory;

/** Creates Java resolver instances through Micronaut. */
@Singleton
public final class MicronautTenantModuleInjectorFactory extends SharedTenantModuleInjectorFactory {
  public MicronautTenantModuleInjectorFactory(BeanContext beanContext) {
    super(
        new CodeInjector() {
          @Override
          public <T> Provider<T> getProvider(Class<T> type) {
            return () -> beanContext.getBean(type);
          }
        });
  }
}
