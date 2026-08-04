package viaduct.tenant.runtime.execution.operationfromannotation;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.GraphQLFragment;
import viaduct.java.api.annotations.GraphQLOperation;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.documents.FragmentFromAnnotation;
import viaduct.java.api.documents.MutationFromAnnotation;
import viaduct.java.api.documents.QueryFromAnnotation;
import viaduct.tenant.runtime.execution.operationfromannotation.resolverbases.ContainerResolvers;
import viaduct.tenant.runtime.execution.operationfromannotation.resolverbases.MutationResolvers;
import viaduct.tenant.runtime.execution.operationfromannotation.resolverbases.QueryResolvers;

public class JavaOperationFromAnnotationContractTest extends OperationFromAnnotationContractTest {
  @GraphQLOperation("query($value: String!) { echo(value: $value) }")
  public static final class EchoQuery extends QueryFromAnnotation {}

  @GraphQLOperation("mutation($value: String!) { record(value: $value) }")
  public static final class RecordMutation extends MutationFromAnnotation {}

  @GraphQLFragment("fragment GreeterFields on Greeter { text }")
  public static final class GreeterFieldsFragment extends FragmentFromAnnotation<Greeter> {}

  @GraphQLOperation("{ greeter { ...GreeterFields } }")
  public static final class GreeterQuery extends QueryFromAnnotation {}

  private static final EchoQuery ECHO_QUERY = new EchoQuery();
  private static final RecordMutation RECORD_MUTATION = new RecordMutation();
  private static final GreeterQuery GREETER_QUERY = new GreeterQuery();

  @Resolver
  public static class ContainerResolver extends QueryResolvers.Container {
    @Override
    public CompletableFuture<Container> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Container.builder(ctx).build());
    }
  }

  @Resolver
  public static class EchoResolver extends QueryResolvers.Echo {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("echo:" + ctx.getArguments().getValue());
    }
  }

  @Resolver
  public static class GreeterResolver extends QueryResolvers.Greeter {
    @Override
    public CompletableFuture<Greeter> resolve(Context ctx) {
      return CompletableFuture.completedFuture(Greeter.builder(ctx).text("hi").build());
    }
  }

  @Resolver
  public static class RecordResolver extends MutationResolvers.Record {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("record:" + ctx.getArguments().getValue());
    }
  }

  @Resolver
  public static class RunQueryWithFragmentResolver extends ContainerResolvers.RunQueryWithFragment {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return ctx.query(GREETER_QUERY).thenApply(result -> result.getGreeter().getText());
    }
  }

  @Resolver
  public static class RunQueryOperationResolver extends ContainerResolvers.RunQueryOperation {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return ctx.query(ECHO_QUERY, Map.of("value", ctx.getArguments().getValue()))
          .thenApply(Query::getEcho);
    }
  }

  @Resolver
  public static class RunMutationOperationResolver extends MutationResolvers.RunMutationOperation {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return ctx.mutation(RECORD_MUTATION, Map.of("value", ctx.getArguments().getValue()))
          .thenApply(Mutation::getRecord);
    }
  }
}
