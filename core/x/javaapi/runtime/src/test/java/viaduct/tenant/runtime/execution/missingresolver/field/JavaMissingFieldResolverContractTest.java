package viaduct.tenant.runtime.execution.missingresolver.field;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.testing.JavaResolverImplementationValidator;
import viaduct.tenant.runtime.execution.missingresolver.field.resolverbases.QueryResolvers;

public class JavaMissingFieldResolverContractTest extends MissingFieldResolverContractTest {

  @Override
  protected void onBeforeBuild() {
    JavaResolverImplementationValidator.validate(
        getClass(), QueryResolvers.Implemented.class, QueryResolvers.Forgotten.class);
  }

  @Resolver
  public static class ImplementedResolver extends QueryResolvers.Implemented {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("present");
    }
  }
}
