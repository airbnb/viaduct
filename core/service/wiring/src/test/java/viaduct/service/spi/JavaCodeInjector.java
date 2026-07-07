package viaduct.service.spi;

import javax.inject.Provider;
import viaduct.service.api.spi.CodeInjector;

/** Java implementation of the {@link CodeInjector} SPI (exercises the method-level generic). */
public final class JavaCodeInjector implements CodeInjector {
  @Override
  public <T> Provider<T> getProvider(Class<T> clazz) {
    return () -> {
      try {
        return clazz.getDeclaredConstructor().newInstance();
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    };
  }
}
