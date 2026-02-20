package viaduct.java.runtime.example;

import org.junit.jupiter.api.Test;
import viaduct.java.runtime.bridge.DefaultJavaResolverClassFinder;
import viaduct.java.runtime.bridge.JavaModuleBootstrapper;
import viaduct.java.runtime.example.resolverbases.QueryResolvers;
import viaduct.java.runtime.test.JavaFeatureTestHelper;
import viaduct.service.api.spi.TenantCodeInjector;

/**
 * End-to-end test demonstrating a Java resolver being called through the Viaduct engine.
 *
 * <p>This test demonstrates the complete flow from a Java developer's perspective:
 *
 * <ul>
 *   <li>Define a GraphQL schema
 *   <li>Create a Java resolver that returns CompletableFuture
 *   <li>Bootstrap the resolver using {@link JavaModuleBootstrapper} with automatic discovery
 *   <li>Execute a GraphQL query through the engine
 *   <li>Verify the response
 * </ul>
 *
 * <p>The bootstrapper automatically discovers resolvers by scanning for:
 *
 * <ul>
 *   <li>Base classes annotated with {@code @ResolverFor} (e.g., {@link QueryResolvers.Greeting})
 *   <li>Implementations annotated with {@code @Resolver} (e.g., {@link GreetingResolver})
 * </ul>
 */
public class GreetingResolverE2ETest {

  private static final String SCHEMA_SDL = "extend type Query { greeting: String }";

  /** Package containing resolver base classes and implementations. */
  private static final String TENANT_PACKAGE = "viaduct.java.runtime.example";

  /** Package containing generated GRT classes. */
  private static final String GRT_PACKAGE = "viaduct.java.runtime.example.grts";

  @Test
  public void greetingResolverReturnsHelloWorldThroughEngine() {
    // Create the class finder that scans for resolver classes
    DefaultJavaResolverClassFinder classFinder =
        new DefaultJavaResolverClassFinder(TENANT_PACKAGE, GRT_PACKAGE);

    // Create the bootstrapper with automatic resolver discovery
    JavaModuleBootstrapper bootstrapper =
        new JavaModuleBootstrapper(classFinder, TenantCodeInjector.Companion.getNaive());

    // Run the feature test
    JavaFeatureTestHelper.run(
        SCHEMA_SDL,
        bootstrapper,
        test -> {
          // Execute a GraphQL query and verify the response
          test.runQueryAndAssert("{ greeting }", "{data: {greeting: \"Hello, World!\"}}");
        });
  }
}
