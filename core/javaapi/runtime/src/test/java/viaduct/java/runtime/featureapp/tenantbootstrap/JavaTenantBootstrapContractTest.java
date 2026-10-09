package viaduct.java.runtime.featureapp.tenantbootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.inject.Provider;
import org.junit.jupiter.api.Test;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.featureapp.tenantbootstrap.resolverbases.QueryResolvers;
import viaduct.service.api.spi.CodeInjector;
import viaduct.service.api.spi.JavaTenantModuleInjectorFactory;
import viaduct.service.api.spi.TenantBootstrapper;
import viaduct.service.api.spi.TenantModuleInjectorFactory;

public class JavaTenantBootstrapContractTest extends TenantBootstrapContract {
  private int bootstrapCalls;
  private int completionCalls;
  private int providerCalls;

  @Override
  protected String featureAppPackagePrefix() {
    return getClass().getPackageName();
  }

  @Override
  protected TenantModuleInjectorFactory tenantModuleInjectorFactory() {
    return new JavaTenantModuleInjectorFactory() {
      @Override
      protected CodeInjector bootstrapBlocking(String tenantName, Class<?> tenantBootstrapClass) {
        assertEquals("viaduct/java/runtime/featureapp/tenantbootstrap", tenantName);
        assertEquals(Bootstrap.class, tenantBootstrapClass);
        bootstrapCalls++;
        GreetingModule module = new Bootstrap();
        return new CodeInjector() {
          @Override
          public <T> Provider<T> getProvider(Class<T> clazz) {
            assertEquals(1, completionCalls);
            assertEquals(GreetingResolver.class, clazz);
            providerCalls++;
            return () -> clazz.cast(new GreetingResolver(module.greeting()));
          }
        };
      }

      @Override
      protected void onBootstrapCompleteBlocking() {
        assertEquals(1, bootstrapCalls);
        completionCalls++;
      }
    };
  }

  @Test
  void generatedRegistryBootstrapsAndInjectsResolverBeforeExecution() {
    var result = execute("{ greeting }");

    assertTrue(result.getErrors().isEmpty());
    assertEquals(Map.of("greeting", "Hello from tenant bootstrap"), result.getData());
    assertEquals(1, bootstrapCalls);
    assertEquals(1, completionCalls);
    assertTrue(providerCalls > 0);

    var secondResult = execute("{ greeting }");

    assertTrue(secondResult.getErrors().isEmpty());
    assertEquals(result.getData(), secondResult.getData());
    assertEquals(1, bootstrapCalls);
    assertEquals(1, completionCalls);
  }

  public interface GreetingModule {
    String greeting();
  }

  @TenantBootstrapper
  public static class Bootstrap implements GreetingModule {
    @Override
    public String greeting() {
      return "Hello from tenant bootstrap";
    }
  }

  @Resolver
  public static class GreetingResolver extends QueryResolvers.Greeting {
    private final String greeting;

    public GreetingResolver(String greeting) {
      this.greeting = greeting;
    }

    @Override
    public CompletableFuture<String> resolve(QueryResolvers.Greeting.Context ctx) {
      return CompletableFuture.completedFuture(greeting);
    }
  }
}
