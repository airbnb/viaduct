package viaduct.tenant.runtime.execution.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    assertEquals(3, Status.values().length);
    assertEquals(Status.ACTIVE, Status.valueOf("ACTIVE"));
    assertEquals(Status.INACTIVE, Status.valueOf("INACTIVE"));
    assertEquals(Status.PENDING, Status.valueOf("PENDING"));
  }
}
