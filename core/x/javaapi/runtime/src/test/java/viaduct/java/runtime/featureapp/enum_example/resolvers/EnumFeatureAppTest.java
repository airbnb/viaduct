package viaduct.java.runtime.featureapp.enum_example.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.runtime.featureapp.enum_example.grt.Status;
import viaduct.java.runtime.featureapp.enum_example.resolverbases.QueryResolvers;
import viaduct.java.runtime.fixtures.JavaFeatureAppTestBase;
import viaduct.service.api.ExecutionResult;

/**
 * Example demonstrating Java FeatureApp pattern with enums.
 *
 * <p>This test shows how to:
 *
 * <ul>
 *   <li>Define a GraphQL enum in the schema
 *   <li>Create a resolver that returns an enum value
 *   <li>Test the resolver using JavaFeatureAppTestBase
 * </ul>
 *
 * <p><b>How it works:</b>
 *
 * <ol>
 *   <li>The Status enum and Query class are defined as test fixtures (simulating codegen output)
 *   <li>QueryResolvers defines the resolver base with @ResolverFor annotation
 *   <li>CurrentStatusResolver extends the generated base and implements resolve()
 *   <li>JavaFeatureAppTestBase bootstraps the resolver via JavaModuleBootstrapper
 *   <li>execute() runs the query through the Viaduct engine
 * </ol>
 */
public class EnumFeatureAppTest extends JavaFeatureAppTestBase {

  /**
   * Returns the SDL schema for Viaduct engine. Do NOT define 'type Query' - Viaduct provides root
   * types automatically. Use only 'extend type Query' for your resolvers.
   */
  @Override
  public String getSdl() {
    return """
    enum Status {
        ACTIVE
        INACTIVE
        PENDING
    }

    extend type Query {
        currentStatus: Status @resolver
    }
    """;
  }

  /**
   * Resolver implementation for Query.currentStatus field.
   *
   * <p>This resolver extends the QueryResolvers.CurrentStatus base class and implements the
   * resolve() method to return a Status enum value.
   */
  @Resolver
  public static class CurrentStatusResolver extends QueryResolvers.CurrentStatus {
    @Override
    public CompletableFuture<Status> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Status.ACTIVE);
    }
  }

  @Test
  public void statusResolverReturnsEnum() {
    ExecutionResult result = execute("{ currentStatus }");

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getData()).isNotNull();

    var data = result.getData();
    assertThat(data.get("currentStatus")).isEqualTo("ACTIVE");
  }

  @Test
  public void allEnumValuesAreAccessible() {
    // Verify that all enum values defined in the schema are available
    assertThat(Status.values()).hasSize(3);
    assertThat(Status.valueOf("ACTIVE")).isEqualTo(Status.ACTIVE);
    assertThat(Status.valueOf("INACTIVE")).isEqualTo(Status.INACTIVE);
    assertThat(Status.valueOf("PENDING")).isEqualTo(Status.PENDING);
  }
}
