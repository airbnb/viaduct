package viaduct.java.api.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import graphql.Scalars;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import viaduct.engine.api.EngineSchema;
import viaduct.java.api.annotations.NodeResolverFor;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.annotations.ResolverFor;
import viaduct.java.api.context.FieldExecutionContext;
import viaduct.java.api.context.NodeExecutionContext;
import viaduct.java.api.internal.BaseBatchedFieldResolver;
import viaduct.java.api.internal.BaseBatchedNodeResolver;
import viaduct.java.api.internal.BaseUnbatchedFieldResolver;
import viaduct.java.api.internal.BaseUnbatchedNodeResolver;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.resolvers.FieldResolverBase;
import viaduct.java.api.resolvers.FieldValue;
import viaduct.java.api.resolvers.NodeResolverBase;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.CompositeOutput;
import viaduct.java.api.types.NodeObject;
import viaduct.java.api.types.Query;

class ResolverTestBaseTest {
  private static final EngineSchema SCHEMA =
      new EngineSchema(
          GraphQLSchema.newSchema()
              .query(
                  GraphQLObjectType.newObject()
                      .name("Query")
                      .field(
                          field("value")
                              .argument(
                                  GraphQLArgument.newArgument()
                                      .name("value")
                                      .type(Scalars.GraphQLString)))
                      .field(field("async"))
                      .field(
                          field("batch")
                              .argument(
                                  GraphQLArgument.newArgument()
                                      .name("value")
                                      .type(Scalars.GraphQLString))))
              .mutation(GraphQLObjectType.newObject().name("Mutation").field(field("change")))
              .additionalType(
                  GraphQLObjectType.newObject().name("TestNode").field(field("value")).build())
              .build());

  private static GraphQLFieldDefinition.Builder field(String name) {
    return GraphQLFieldDefinition.newFieldDefinition().name(name).type(Scalars.GraphQLString);
  }

  private record TestQuery(String value) implements Query {}

  private record TestArgs(String value) implements Arguments {}

  private record TestNode(String value) implements NodeObject {}

  private static ResolverTestBase.FieldInputs<TestQuery, TestQuery, TestArgs> batchInputs(
      String object, String query, String argument, String request) {
    return new ResolverTestBase.FieldInputs<TestQuery, TestQuery, TestArgs>()
        .objectValue(new TestQuery(object))
        .queryValue(new TestQuery(query))
        .arguments(new TestArgs(argument))
        .requestContext(request);
  }

  @ResolverFor(typeName = "Query", fieldName = "value", isSelective = false)
  @Resolver(objectValueFragment = "value", queryValueFragment = "value")
  private static final class FieldResolver
      implements FieldResolverBase<String, TestQuery, TestQuery, TestArgs, CompositeOutput>,
          BaseUnbatchedFieldResolver {
    @Override
    public CompletableFuture<?> invokeFieldResolver(FieldExecutionContext<?, ?, ?, ?> ctx) {
      TestQuery object = (TestQuery) ctx.getObjectValue();
      TestQuery query = (TestQuery) ctx.getQueryValue();
      TestArgs args = (TestArgs) ctx.getArguments();
      return ctx.query("lookup", Map.of("id", args.value()), TestQuery.class)
          .thenApply(
              result ->
                  object.value()
                      + query.value()
                      + args.value()
                      + ctx.getRequestContext()
                      + result.value());
    }
  }

  @ResolverFor(typeName = "Query", fieldName = "async", isSelective = false)
  private static final class AsyncFieldResolver
      implements FieldResolverBase<
              String, TestQuery, TestQuery, Arguments.NoArguments, CompositeOutput>,
          BaseUnbatchedFieldResolver {
    private final CompletableFuture<String> result;

    private AsyncFieldResolver(CompletableFuture<String> result) {
      this.result = result;
    }

    @Override
    public CompletableFuture<?> invokeFieldResolver(FieldExecutionContext<?, ?, ?, ?> context) {
      return result;
    }
  }

  @ResolverFor(typeName = "Mutation", fieldName = "change", isSelective = false)
  private static final class MutationResolver
      implements FieldResolverBase<
              String, TestQuery, TestQuery, Arguments.NoArguments, CompositeOutput>,
          BaseUnbatchedFieldResolver {
    @Override
    public CompletableFuture<?> invokeFieldResolver(FieldExecutionContext<?, ?, ?, ?> context) {
      return context
          .mutation("apply", Map.of(), TestQuery.class)
          .thenApply(result -> result.value() + context.getRequestContext());
    }
  }

  @ResolverFor(typeName = "Query", fieldName = "batch", isSelective = false, isBatching = true)
  @Resolver(objectValueFragment = "value", queryValueFragment = "value")
  private static final class FieldBatchResolver
      implements FieldResolverBase<String, TestQuery, TestQuery, TestArgs, CompositeOutput>,
          BaseBatchedFieldResolver {
    private CompletableFuture<Map<FieldExecutionContext<?, ?, ?, ?>, FieldValue<?>>> result;

    @Override
    public CompletableFuture<Map<FieldExecutionContext<?, ?, ?, ?>, Object>>
        invokeFieldBatchResolver(List<FieldExecutionContext<?, ?, ?, ?>> contexts) {
      throw new AssertionError("The per-item error adapter must be used");
    }

    @Override
    public CompletableFuture<Map<FieldExecutionContext<?, ?, ?, ?>, FieldValue<?>>>
        invokeFieldBatchResolverWithErrors(List<FieldExecutionContext<?, ?, ?, ?>> contexts) {
      if (result != null) return result;
      Map<FieldExecutionContext<?, ?, ?, ?>, FieldValue<?>> values = new HashMap<>();
      for (FieldExecutionContext<?, ?, ?, ?> context : contexts) {
        String value = ((TestQuery) context.getObjectValue()).value();
        values.put(
            context,
            "bad".equals(value)
                ? FieldValue.ofError(new IllegalStateException("per-item failure"))
                : FieldValue.ofValue(
                    value
                        + ((TestQuery) context.getQueryValue()).value()
                        + ((TestArgs) context.getArguments()).value()
                        + context.getRequestContext()));
      }
      return CompletableFuture.completedFuture(values);
    }
  }

  @NodeResolverFor(typeName = "TestNode", isBatching = false)
  private static final class NodeResolver
      implements NodeResolverBase<TestNode>, BaseUnbatchedNodeResolver {
    @Override
    public CompletableFuture<?> invokeNodeResolver(NodeExecutionContext<?> context) {
      if ("bad".equals(context.getId().getInternalID())) {
        return CompletableFuture.failedFuture(new IllegalStateException("node failure"));
      }
      return CompletableFuture.completedFuture(
          new TestNode(context.getId().getInternalID() + context.getRequestContext()));
    }
  }

  @NodeResolverFor(typeName = "TestNode", isBatching = true)
  private static final class NodeBatchResolver
      implements NodeResolverBase<TestNode>, BaseBatchedNodeResolver<TestNode> {
    private CompletableFuture<Map<NodeExecutionContext<?>, FieldValue<TestNode>>> result;

    @Override
    public CompletableFuture<Map<NodeExecutionContext<?>, FieldValue<TestNode>>>
        invokeNodeBatchResolver(List<NodeExecutionContext<?>> contexts) {
      if (result != null) return result;
      Map<NodeExecutionContext<?>, FieldValue<TestNode>> values = new HashMap<>();
      for (NodeExecutionContext<?> context : contexts) {
        String id = context.getId().getInternalID();
        values.put(
            context,
            "bad".equals(id)
                ? FieldValue.ofError(new IllegalStateException("node failure"))
                : FieldValue.ofValue(new TestNode(id + context.getRequestContext())));
      }
      return CompletableFuture.completedFuture(values);
    }
  }

  @Test
  void fieldInputsAndPreparedQueryAreObserved() {
    ResolverTestBase test = new ResolverTestBase(SCHEMA);
    var inputs =
        new ResolverTestBase.FieldInputs<TestQuery, TestQuery, TestArgs>()
            .objectValue(new TestQuery("object"))
            .queryValue(new TestQuery("query"))
            .arguments(new TestArgs("arg"))
            .requestContext("request")
            .onQuery(
                "lookup",
                Map.of("id", "arg"),
                TestQuery.class,
                CompletableFuture.completedFuture(new TestQuery("prepared")));

    assertThat(test.runFieldResolver(new FieldResolver(), inputs).join())
        .isEqualTo("objectqueryargrequestprepared");
    assertThatThrownBy(
            () ->
                test.runFieldResolver(
                    new FieldResolver(),
                    new ResolverTestBase.FieldInputs<TestQuery, TestQuery, TestArgs>()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("object value");
    assertThatThrownBy(
            () ->
                test.runFieldResolver(
                    new FieldResolver(),
                    new ResolverTestBase.FieldInputs<TestQuery, TestQuery, TestArgs>()
                        .objectValue(new TestQuery("object"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("query value");
    assertThatThrownBy(
            () ->
                test.runFieldResolver(
                    new FieldResolver(),
                    new ResolverTestBase.FieldInputs<TestQuery, TestQuery, TestArgs>()
                        .objectValue(new TestQuery("object"))
                        .queryValue(new TestQuery("query"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("explicit arguments");
    assertThatThrownBy(
            () ->
                test.runFieldResolver(
                    new AsyncFieldResolver(new CompletableFuture<>()),
                    new ResolverTestBase.FieldInputs<TestQuery, TestQuery, Arguments.NoArguments>()
                        .objectValue(new TestQuery("not declared"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no object value fragment");
  }

  @Test
  void mutationUsesExplicitPreparedResultAndFieldKindIsChecked() {
    ResolverTestBase test = new ResolverTestBase(SCHEMA);
    var inputs =
        new ResolverTestBase.FieldInputs<TestQuery, TestQuery, Arguments.NoArguments>()
            .requestContext("request")
            .onMutation(
                "apply",
                Map.of(),
                TestQuery.class,
                CompletableFuture.completedFuture(new TestQuery("done")));

    assertThat(test.runMutationFieldResolver(new MutationResolver(), inputs).join())
        .isEqualTo("donerequest");
    assertThatThrownBy(() -> test.runFieldResolver(new MutationResolver(), inputs))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                test.runMutationFieldResolver(
                        new MutationResolver(),
                        new ResolverTestBase.FieldInputs<
                            TestQuery, TestQuery, Arguments.NoArguments>())
                    .join())
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                test.runMutationFieldResolver(
                        new MutationResolver(),
                        new ResolverTestBase.FieldInputs<
                                TestQuery, TestQuery, Arguments.NoArguments>()
                            .onMutation(
                                "apply",
                                Map.of(),
                                TestQuery.class,
                                CompletableFuture.failedFuture(
                                    new IllegalStateException("mutation failed"))))
                    .join())
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void fieldBatchKeepsInputOrderAndPerItemErrors() {
    ResolverTestBase test = new ResolverTestBase(SCHEMA);
    var inputs =
        List.of(batchInputs("first", "query", "arg", "1"), batchInputs("bad", "query", "arg", "2"));

    var values = test.runFieldBatchResolver(new FieldBatchResolver(), inputs).join();
    assertThat(values.get(0).get()).isEqualTo("firstqueryarg1");
    assertThat(values.get(1).isError()).isTrue();
    assertThatThrownBy(() -> values.get(1).get()).hasMessage("per-item failure");
  }

  @Test
  void nodeContextUsesTypedGlobalIdAndRequestContext() {
    ResolverTestBase test = new ResolverTestBase(SCHEMA);
    var id = test.globalIDFor(Type.ofClass(TestNode.class), "n1");
    var inputs = new ResolverTestBase.NodeInputs<>(id).requestContext("request");

    assertThat(test.runNodeResolver(new NodeResolver(), inputs).join().value())
        .isEqualTo("n1request");
    assertThat(test.context().serialize(id))
        .isEqualTo(test.context().serialize(test.globalIDFor(Type.ofClass(TestNode.class), "n1")));
    assertThatThrownBy(
            () ->
                test.runNodeResolver(
                        new NodeResolver(),
                        new ResolverTestBase.NodeInputs<>(
                            test.globalIDFor(Type.ofClass(TestNode.class), "bad")))
                    .join())
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void nodeBatchKeepsInputOrderAndPerItemErrors() {
    ResolverTestBase test = new ResolverTestBase(SCHEMA);
    var inputs =
        List.of(
            new ResolverTestBase.NodeInputs<>(
                    test.globalIDFor(Type.ofClass(TestNode.class), "first"))
                .requestContext("1"),
            new ResolverTestBase.NodeInputs<>(test.globalIDFor(Type.ofClass(TestNode.class), "bad"))
                .requestContext("2"));

    var values = test.runNodeBatchResolver(new NodeBatchResolver(), inputs).join();
    assertThat(values.get(0).get().value()).isEqualTo("first1");
    assertThat(values.get(1).isError()).isTrue();
    assertThatThrownBy(() -> values.get(1).get()).hasMessage("node failure");
  }

  @Test
  void futuresKeepFailuresAndCancellationObservable() {
    ResolverTestBase test = new ResolverTestBase(SCHEMA);
    var failed = CompletableFuture.<String>failedFuture(new IllegalStateException("async failure"));
    var result =
        test.runFieldResolver(
            new AsyncFieldResolver(failed),
            new ResolverTestBase.FieldInputs<TestQuery, TestQuery, Arguments.NoArguments>());
    assertThat(result).isSameAs(failed);
    assertThatThrownBy(result::join).hasCauseInstanceOf(IllegalStateException.class);

    var pending = new CompletableFuture<String>();
    var cancellable =
        test.runFieldResolver(
            new AsyncFieldResolver(pending),
            new ResolverTestBase.FieldInputs<TestQuery, TestQuery, Arguments.NoArguments>());
    assertThat(cancellable.cancel(false)).isTrue();
    assertThat(pending.isCancelled()).isTrue();
    assertThatThrownBy(cancellable::join).isInstanceOf(CancellationException.class);

    var batch = new FieldBatchResolver();
    batch.result = new CompletableFuture<>();
    var batchResult =
        test.runFieldBatchResolver(batch, List.of(batchInputs("first", "query", "arg", "1")));
    assertThat(batchResult.cancel(false)).isTrue();
    assertThat(batch.result.isCancelled()).isTrue();

    var failedBatch = new FieldBatchResolver();
    failedBatch.result = new CompletableFuture<>();
    var failedBatchResult =
        test.runFieldBatchResolver(failedBatch, List.of(batchInputs("first", "query", "arg", "1")));
    failedBatch.result.completeExceptionally(new IllegalStateException("batch failed"));
    assertThatThrownBy(failedBatchResult::join).hasCauseInstanceOf(IllegalStateException.class);

    var nodeBatch = new NodeBatchResolver();
    nodeBatch.result = new CompletableFuture<>();
    var nodeResult =
        test.runNodeBatchResolver(
            nodeBatch,
            List.of(
                new ResolverTestBase.NodeInputs<>(
                    test.globalIDFor(Type.ofClass(TestNode.class), "n1"))));
    assertThat(nodeResult.cancel(false)).isTrue();
    assertThat(nodeBatch.result.isCancelled()).isTrue();

    var cancelledNodeBatch = new NodeBatchResolver();
    cancelledNodeBatch.result = new CompletableFuture<>();
    var cancelledNodeResult =
        test.runNodeBatchResolver(
            cancelledNodeBatch,
            List.of(
                new ResolverTestBase.NodeInputs<>(
                    test.globalIDFor(Type.ofClass(TestNode.class), "n2"))));
    cancelledNodeBatch.result.cancel(false);
    assertThat(cancelledNodeResult.isCancelled()).isTrue();
  }
}
