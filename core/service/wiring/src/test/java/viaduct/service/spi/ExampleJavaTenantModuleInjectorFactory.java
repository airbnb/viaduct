package viaduct.service.spi;

import org.jspecify.annotations.NonNull;
import viaduct.service.api.spi.CodeInjector;
import viaduct.service.api.spi.JavaTenantModuleInjectorFactory;

/**
 * Java tenant built on the {@link JavaTenantModuleInjectorFactory} SPI adapter.
 *
 * <p>A Java tenant extends {@link JavaTenantModuleInjectorFactory} and implements {@code
 * bootstrapBlocking}; the base class runs it on an executor and satisfies the framework's {@code
 * suspend} {@code bootstrap}. This proves the SPI is implementable from Java without touching
 * Kotlin coroutines.
 */
public final class ExampleJavaTenantModuleInjectorFactory extends JavaTenantModuleInjectorFactory {

  private final CodeInjector codeInjector = new JavaCodeInjector();

  @Override
  public @NonNull CodeInjector bootstrapBlocking(
      @NonNull String tenantName, Class<?> tenantBootstrapClass) {
    return codeInjector;
  }
}
