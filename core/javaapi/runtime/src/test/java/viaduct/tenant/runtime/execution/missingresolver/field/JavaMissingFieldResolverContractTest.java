package viaduct.tenant.runtime.execution.missingresolver.field;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.tenant.runtime.execution.missingresolver.field.resolverbases.QueryResolvers;

public class JavaMissingFieldResolverContractTest extends MissingFieldResolverContractTest {

  @Resolver
  public static class ImplementedResolver extends QueryResolvers.Implemented {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("present");
    }
  }
}
