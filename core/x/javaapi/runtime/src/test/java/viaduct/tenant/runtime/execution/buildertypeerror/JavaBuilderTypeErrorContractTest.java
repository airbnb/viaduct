package viaduct.tenant.runtime.execution.buildertypeerror;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.tenant.runtime.execution.buildertypeerror.resolverbases.QueryResolvers;

public class JavaBuilderTypeErrorContractTest extends BuilderTypeErrorContractTest {

  @Resolver
  public static class ContainerResolver extends QueryResolvers.Container {
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public CompletableFuture<Container> resolve(Context ctx) {
      Item item = Item.builder(ctx).name("wrong-type").build();
      List<Tag> wrongTypedList = (List) List.of(item);
      return CompletableFuture.completedFuture(Container.builder(ctx).tags(wrongTypedList).build());
    }
  }
}
