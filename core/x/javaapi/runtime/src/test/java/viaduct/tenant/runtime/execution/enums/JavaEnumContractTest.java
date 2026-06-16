package viaduct.tenant.runtime.execution.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import viaduct.java.api.annotations.Resolver;
import viaduct.tenant.runtime.execution.enums.resolverbases.QueryResolvers;

public class JavaEnumContractTest extends EnumContractTest {

  // --- Resolvers ---

  @Resolver
  public static class CurrentStatusResolver extends QueryResolvers.CurrentStatus {
    @Override
    public CompletableFuture<Status> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Status.ACTIVE);
    }
  }

  @Resolver
  public static class StatusFromRequestContextResolver
      extends QueryResolvers.StatusFromRequestContext {
    @Override
    public CompletableFuture<Status> resolve(Context ctx) {
      Object rc = ctx.getRequestContext();
      return CompletableFuture.completedFuture(rc instanceof String s ? Status.valueOf(s) : null);
    }
  }

  // --- Java-only test ---

  @Test
  public void allEnumValuesAreAccessible() {
    assertThat(Status.values()).hasSize(3);
    assertThat(Status.valueOf("ACTIVE")).isEqualTo(Status.ACTIVE);
    assertThat(Status.valueOf("INACTIVE")).isEqualTo(Status.INACTIVE);
    assertThat(Status.valueOf("PENDING")).isEqualTo(Status.PENDING);
  }
}
