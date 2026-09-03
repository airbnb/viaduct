package viaduct.tenant.runtime.execution.roottypes;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.GraphQLOperation;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.documents.MutationFromAnnotation;
import viaduct.java.api.documents.QueryFromAnnotation;
import viaduct.tenant.runtime.execution.roottypes.resolverbases.CustomMutationResolvers;
import viaduct.tenant.runtime.execution.roottypes.resolverbases.CustomQueryResolvers;

public class JavaRootTypesSchemaClauseContractTest extends RootTypesSchemaClauseContractTest {
  @GraphQLOperation("query($name: String!) { greeting(name: $name) }")
  public static final class GreetingQuery extends QueryFromAnnotation {}

  @GraphQLOperation(
      "mutation($content: String!) { saveMessage(content: $content) { messageId content } }")
  public static final class SaveMessageMutation extends MutationFromAnnotation {}

  private static final GreetingQuery GREETING_QUERY = new GreetingQuery();
  private static final SaveMessageMutation SAVE_MESSAGE_MUTATION = new SaveMessageMutation();

  @Resolver
  public static class GreetingResolver extends CustomQueryResolvers.Greeting {
    @Override
    public CompletableFuture<String> resolve(CustomQueryResolvers.Greeting.Context ctx) {
      return CompletableFuture.completedFuture("Hello, " + ctx.getArguments().getName() + "!");
    }
  }

  @Resolver
  public static class EchoResolver extends CustomQueryResolvers.Echo {
    @Override
    public CompletableFuture<String> resolve(CustomQueryResolvers.Echo.Context ctx) {
      return CompletableFuture.completedFuture(ctx.getArguments().getMessage());
    }
  }

  @Resolver(queryValueFragment = "fragment _ on CustomQuery { greeting(name: \"Selection\") }")
  public static class SelectedGreetingResolver extends CustomQueryResolvers.SelectedGreeting {
    @Override
    public CompletableFuture<String> resolve(CustomQueryResolvers.SelectedGreeting.Context ctx) {
      return CompletableFuture.completedFuture(ctx.getQueryValue().getGreetingOrThrow());
    }
  }

  @Resolver
  public static class QueriedGreetingResolver extends CustomQueryResolvers.QueriedGreeting {
    @Override
    public CompletableFuture<String> resolve(CustomQueryResolvers.QueriedGreeting.Context ctx) {
      return ctx.query(GREETING_QUERY, Map.of("name", ctx.getArguments().getName()))
          .thenApply(CustomQuery::getGreetingOrThrow);
    }
  }

  @Resolver
  public static class SaveMessageResolver extends CustomMutationResolvers.SaveMessage {
    @Override
    public CompletableFuture<SaveMessagePayload> resolve(
        CustomMutationResolvers.SaveMessage.Context ctx) {
      String content = ctx.getArguments().getContent();
      return CompletableFuture.completedFuture(
          SaveMessagePayload.builder(ctx)
              .messageId("msg-" + content.hashCode())
              .content(content)
              .build());
    }
  }

  @Resolver
  public static class RelayMessageResolver extends CustomMutationResolvers.RelayMessage {
    @Override
    public CompletableFuture<SaveMessagePayload> resolve(
        CustomMutationResolvers.RelayMessage.Context ctx) {
      return ctx.mutation(SAVE_MESSAGE_MUTATION, Map.of("content", ctx.getArguments().getContent()))
          .thenApply(CustomMutation::getSaveMessageOrThrow);
    }
  }
}
