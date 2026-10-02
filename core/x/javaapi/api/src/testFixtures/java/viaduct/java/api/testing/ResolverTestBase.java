package viaduct.java.api.testing;

import graphql.schema.GraphQLInputObjectType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import viaduct.api.internal.InputTypeFactory;
import viaduct.engine.api.EngineSchema;
import viaduct.java.api.annotations.NodeResolverFor;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.annotations.ResolverFor;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.context.FieldExecutionContext;
import viaduct.java.api.context.NodeExecutionContext;
import viaduct.java.api.context.RootFieldCall;
import viaduct.java.api.documents.MutationFromAnnotation;
import viaduct.java.api.documents.QueryFromAnnotation;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.BaseBatchedFieldResolver;
import viaduct.java.api.internal.BaseBatchedNodeResolver;
import viaduct.java.api.internal.BaseUnbatchedFieldResolver;
import viaduct.java.api.internal.BaseUnbatchedNodeResolver;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.resolvers.FieldResolverBase;
import viaduct.java.api.resolvers.FieldValue;
import viaduct.java.api.resolvers.NodeResolverBase;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.CompositeOutput;
import viaduct.java.api.types.GraphQLObject;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.java.api.types.NodeObject;
import viaduct.java.api.types.Query;
import viaduct.service.api.spi.GlobalIDCodec;
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault;

/** Isolated execution of generated Java resolver adapters against explicit test inputs. */
public final class ResolverTestBase {
  private final EngineSchema schema;
  private final GlobalIDCodec codec;
  private final Map<String, Type<?>> nodeTypes = new ConcurrentHashMap<>();

  public ResolverTestBase(EngineSchema schema) {
    this(schema, GlobalIDCodecDefault.INSTANCE);
  }

  public ResolverTestBase(EngineSchema schema, GlobalIDCodec codec) {
    this.schema = Objects.requireNonNull(schema);
    this.codec = Objects.requireNonNull(codec);
  }

  public ExecutionContext context() {
    return new TestContext(new FieldInputs<>(), null, null, null, false);
  }

  public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String internalId) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(internalId);
    if (schema.getSchema().getObjectType(type.getName()) == null) {
      throw new IllegalArgumentException("Node type not in schema: " + type.getName());
    }
    Type<?> existing = nodeTypes.putIfAbsent(type.getName(), type);
    if (existing != null && !existing.equals(type)) {
      throw new IllegalArgumentException("Conflicting Java types for node " + type.getName());
    }
    return new TestGlobalID<>(type, internalId);
  }

  public <
          T,
          O extends GraphQLObject,
          Q extends Query,
          A extends Arguments,
          S extends CompositeOutput>
      CompletableFuture<T> runFieldResolver(
          FieldResolverBase<T, O, Q, A, S> resolver, FieldInputs<O, Q, A> inputs) {
    checkField(resolver, false, false);
    checkInputs(resolver, inputs);
    return invokeSingle(
        () ->
            ((BaseUnbatchedFieldResolver) resolver)
                .invokeFieldResolver(fieldContext(inputs, false)));
  }

  public <
          T,
          O extends GraphQLObject,
          Q extends Query,
          A extends Arguments,
          S extends CompositeOutput>
      CompletableFuture<T> runMutationFieldResolver(
          FieldResolverBase<T, O, Q, A, S> resolver, FieldInputs<O, Q, A> inputs) {
    checkField(resolver, false, true);
    checkInputs(resolver, inputs);
    return invokeSingle(
        () ->
            ((BaseUnbatchedFieldResolver) resolver)
                .invokeFieldResolver(fieldContext(inputs, true)));
  }

  public <
          T,
          O extends GraphQLObject,
          Q extends Query,
          A extends Arguments,
          S extends CompositeOutput>
      CompletableFuture<List<FieldValue<T>>> runFieldBatchResolver(
          FieldResolverBase<T, O, Q, A, S> resolver, List<FieldInputs<O, Q, A>> inputs) {
    checkField(resolver, true, false);
    inputs.forEach(input -> checkInputs(resolver, input));
    List<FieldExecutionContext<?, ?, ?, ?>> contexts = new ArrayList<>(inputs.size());
    for (FieldInputs<O, Q, A> input : inputs) {
      contexts.add(fieldContext(input, false));
    }
    CompletableFuture<Map<FieldExecutionContext<?, ?, ?, ?>, FieldValue<?>>> source;
    try {
      source = ((BaseBatchedFieldResolver) resolver).invokeFieldBatchResolverWithErrors(contexts);
    } catch (Exception exception) {
      return CompletableFuture.failedFuture(exception);
    }
    return mapFuture(source, results -> orderedResults(contexts, results));
  }

  public <R extends NodeObject> CompletableFuture<R> runNodeResolver(
      NodeResolverBase<R> resolver, NodeInputs<R> inputs) {
    checkNode(resolver, false, inputs);
    return invokeSingle(
        () -> ((BaseUnbatchedNodeResolver) resolver).invokeNodeResolver(nodeContext(inputs)));
  }

  public <R extends NodeObject> CompletableFuture<List<FieldValue<R>>> runNodeBatchResolver(
      NodeResolverBase<R> resolver, List<NodeInputs<R>> inputs) {
    checkNodeResolver(resolver, true);
    for (NodeInputs<R> input : inputs) {
      checkNode(resolver, true, input);
    }
    List<NodeExecutionContext<?>> contexts = new ArrayList<>(inputs.size());
    for (NodeInputs<R> input : inputs) {
      contexts.add(nodeContext(input));
    }
    CompletableFuture<Map<NodeExecutionContext<?>, FieldValue<R>>> source;
    try {
      source = ((BaseBatchedNodeResolver<R>) resolver).invokeNodeBatchResolver(contexts);
    } catch (Exception exception) {
      return CompletableFuture.failedFuture(exception);
    }
    return mapFuture(source, results -> orderedResults(contexts, results));
  }

  private <O extends GraphQLObject, Q extends Query, A extends Arguments>
      FieldExecutionContext<?, ?, ?, ?> fieldContext(
          FieldInputs<O, Q, A> inputs, boolean mutation) {
    return new TestContext(
        inputs, inputs.objectValue, inputs.queryValue, inputs.arguments, mutation);
  }

  private void checkInputs(FieldResolverBase<?, ?, ?, ?, ?> resolver, FieldInputs<?, ?, ?> inputs) {
    Resolver annotation = resolver.getClass().getAnnotation(Resolver.class);
    boolean needsObject = annotation != null && !annotation.objectValueFragment().isEmpty();
    boolean needsQuery = annotation != null && !annotation.queryValueFragment().isEmpty();
    if (needsObject && inputs.objectValue == null) {
      throw new IllegalArgumentException("Resolver requires an object value");
    }
    if (needsQuery && inputs.queryValue == null) {
      throw new IllegalArgumentException("Resolver requires a query value");
    }
    if (!needsObject && inputs.objectValue != null) {
      throw new IllegalArgumentException("Resolver has no object value fragment");
    }
    if (!needsQuery && inputs.queryValue != null) {
      throw new IllegalArgumentException("Resolver has no query value fragment");
    }
    ResolverFor metadata = annotationOnBase(resolver.getClass(), ResolverFor.class);
    var field =
        schema
            .getSchema()
            .getObjectType(metadata.typeName())
            .getFieldDefinition(metadata.fieldName());
    if (!field.getArguments().isEmpty() && inputs.arguments == null) {
      throw new IllegalArgumentException(
          "Resolver requires explicit arguments for "
              + metadata.typeName()
              + "."
              + metadata.fieldName());
    }
    if (field.getArguments().isEmpty()
        && inputs.arguments != null
        && !(inputs.arguments instanceof Arguments.NoArguments)) {
      throw new IllegalArgumentException(
          "Resolver field has no arguments: " + metadata.typeName() + "." + metadata.fieldName());
    }
  }

  private <R extends NodeObject> TestContext nodeContext(NodeInputs<R> inputs) {
    return new TestContext(inputs, null, null, null, false, inputs.id);
  }

  private void checkField(
      FieldResolverBase<?, ?, ?, ?, ?> resolver, boolean batch, boolean mutation) {
    ResolverFor metadata = annotationOnBase(resolver.getClass(), ResolverFor.class);
    if (metadata == null || metadata.isBatching() != batch || metadata.isSelective()) {
      throw new IllegalArgumentException(
          "Expected a generated non-selective "
              + (batch ? "batched" : "single")
              + " field resolver base");
    }
    String mutationType =
        schema.getSchema().getMutationType() == null
            ? null
            : schema.getSchema().getMutationType().getName();
    if (mutation != metadata.typeName().equals(mutationType)) {
      throw new IllegalArgumentException("Use the resolver method matching " + metadata.typeName());
    }
    var type = schema.getSchema().getObjectType(metadata.typeName());
    if (type == null || type.getFieldDefinition(metadata.fieldName()) == null) {
      throw new IllegalArgumentException(
          "Resolver field not in schema: " + metadata.typeName() + "." + metadata.fieldName());
    }
    if ((batch && !(resolver instanceof BaseBatchedFieldResolver))
        || (!batch && !(resolver instanceof BaseUnbatchedFieldResolver))) {
      throw new IllegalArgumentException("Resolver is missing its generated field adapter");
    }
  }

  private <R extends NodeObject> void checkNode(
      NodeResolverBase<R> resolver, boolean batch, NodeInputs<R> inputs) {
    NodeResolverFor metadata = checkNodeResolver(resolver, batch);
    if (inputs.id == null || !metadata.typeName().equals(inputs.id.getType().getName())) {
      throw new IllegalArgumentException("Node ID must have type " + metadata.typeName());
    }
  }

  private <R extends NodeObject> NodeResolverFor checkNodeResolver(
      NodeResolverBase<R> resolver, boolean batch) {
    NodeResolverFor metadata = annotationOnBase(resolver.getClass(), NodeResolverFor.class);
    if (metadata == null || metadata.isBatching() != batch || metadata.isSelective()) {
      throw new IllegalArgumentException(
          "Expected a generated non-selective "
              + (batch ? "batched" : "single")
              + " node resolver base");
    }
    if (schema.getSchema().getObjectType(metadata.typeName()) == null) {
      throw new IllegalArgumentException("Node type not in schema: " + metadata.typeName());
    }
    if (batch && !(resolver instanceof BaseBatchedNodeResolver<?>)) {
      throw new IllegalArgumentException("Resolver is missing its generated node batch adapter");
    }
    if (!batch && !(resolver instanceof BaseUnbatchedNodeResolver)) {
      throw new IllegalArgumentException("Resolver is missing its generated node adapter");
    }
    return metadata;
  }

  private static <A extends java.lang.annotation.Annotation> A annotationOnBase(
      Class<?> resolverClass, Class<A> annotationClass) {
    for (Class<?> current = resolverClass; current != null; current = current.getSuperclass()) {
      A annotation = current.getDeclaredAnnotation(annotationClass);
      if (annotation != null) {
        return annotation;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static <T> CompletableFuture<T> invokeSingle(
      java.util.function.Supplier<CompletableFuture<?>> invocation) {
    try {
      return (CompletableFuture<T>)
          Objects.requireNonNull(invocation.get(), "Resolver returned null future");
    } catch (Exception exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }

  @SuppressWarnings("unchecked")
  private static <C, T> List<FieldValue<T>> orderedResults(
      List<C> contexts, Map<C, ? extends FieldValue<?>> results) {
    if (results == null) {
      throw new IllegalArgumentException("Batch resolver returned a null result map");
    }
    List<FieldValue<T>> ordered = new ArrayList<>(contexts.size());
    for (C context : contexts) {
      if (!results.containsKey(context) || results.get(context) == null) {
        throw new IllegalArgumentException("Batch resolver omitted a context result");
      }
      ordered.add((FieldValue<T>) results.get(context));
    }
    if (results.size() != contexts.size()) {
      throw new IllegalArgumentException("Batch resolver returned an unknown context");
    }
    return List.copyOf(ordered);
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private static <S, T> CompletableFuture<T> mapFuture(
      CompletableFuture<S> source, Function<S, T> mapper) {
    if (source == null) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Resolver returned null future"));
    }
    CompletableFuture<T> mapped = new CompletableFuture<>();
    source.whenComplete(
        (value, error) -> {
          if (source.isCancelled()) {
            mapped.cancel(false);
          } else if (error != null) {
            mapped.completeExceptionally(error);
          } else {
            try {
              mapped.complete(mapper.apply(value));
            } catch (Throwable exception) {
              mapped.completeExceptionally(exception);
            }
          }
        });
    mapped.whenComplete(
        (value, error) -> {
          if (mapped.isCancelled()) {
            source.cancel(false);
          }
        });
    return mapped;
  }

  public static class Inputs<I extends Inputs<I>> {
    private Object requestContext;
    private final Map<Subquery, CompletableFuture<?>> results = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public I requestContext(Object value) {
      requestContext = value;
      return (I) this;
    }

    @SuppressWarnings("unchecked")
    public <T> I onQuery(
        String selections,
        Map<String, Object> variables,
        Class<T> type,
        CompletableFuture<T> result) {
      results.put(new Subquery(false, selections, variables, type), Objects.requireNonNull(result));
      return (I) this;
    }

    @SuppressWarnings("unchecked")
    public <T> I onMutation(
        String selections,
        Map<String, Object> variables,
        Class<T> type,
        CompletableFuture<T> result) {
      results.put(new Subquery(true, selections, variables, type), Objects.requireNonNull(result));
      return (I) this;
    }
  }

  public static final class FieldInputs<
          O extends GraphQLObject, Q extends Query, A extends Arguments>
      extends Inputs<FieldInputs<O, Q, A>> {
    private O objectValue;
    private Q queryValue;
    private A arguments;

    public FieldInputs<O, Q, A> objectValue(O value) {
      objectValue = value;
      return this;
    }

    public FieldInputs<O, Q, A> queryValue(Q value) {
      queryValue = value;
      return this;
    }

    public FieldInputs<O, Q, A> arguments(A value) {
      arguments = value;
      return this;
    }
  }

  public static final class NodeInputs<R extends NodeObject> extends Inputs<NodeInputs<R>> {
    private final GlobalID<R> id;

    public NodeInputs(GlobalID<R> id) {
      this.id = Objects.requireNonNull(id);
    }
  }

  private record Subquery(
      boolean mutation, String selections, Map<String, Object> variables, Class<?> type) {
    private Subquery {
      Objects.requireNonNull(selections);
      Objects.requireNonNull(type);
      variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }
  }

  private record TestGlobalID<T extends NodeCompositeOutput>(Type<T> type, String internalId)
      implements GlobalID<T> {
    @Override
    public Type<T> getType() {
      return type;
    }

    @Override
    public String getInternalID() {
      return internalId;
    }
  }

  private final class TestContext
      implements FieldExecutionContext<GraphQLObject, Query, Arguments, CompositeOutput>,
          NodeExecutionContext<NodeObject>,
          InternalContext {
    private final Object requestContext;
    private final Map<Subquery, CompletableFuture<?>> results;
    private final GraphQLObject objectValue;
    private final Query queryValue;
    private final Arguments arguments;
    private final boolean mutationAllowed;
    private final GlobalID<?> id;

    private TestContext(
        Inputs<?> inputs,
        GraphQLObject objectValue,
        Query queryValue,
        Arguments arguments,
        boolean mutationAllowed) {
      this(inputs, objectValue, queryValue, arguments, mutationAllowed, null);
    }

    private TestContext(
        Inputs<?> inputs,
        GraphQLObject objectValue,
        Query queryValue,
        Arguments arguments,
        boolean mutationAllowed,
        GlobalID<?> id) {
      Objects.requireNonNull(inputs);
      this.requestContext = inputs.requestContext;
      this.results = Map.copyOf(inputs.results);
      this.objectValue = objectValue;
      this.queryValue = queryValue;
      this.arguments = arguments;
      this.mutationAllowed = mutationAllowed;
      this.id = id;
    }

    @Override
    public GraphQLObject getObjectValue() {
      if (objectValue == null) throw new IllegalStateException("Object value was not configured");
      return objectValue;
    }

    @Override
    public Query getQueryValue() {
      if (queryValue == null) throw new IllegalStateException("Query value was not configured");
      return queryValue;
    }

    @Override
    public Arguments getArguments() {
      return arguments == null ? Arguments.None : arguments;
    }

    @SuppressWarnings("unchecked")
    @Override
    public GlobalID<NodeObject> getId() {
      if (id == null) throw new IllegalStateException("Node ID was not configured");
      return (GlobalID<NodeObject>) id;
    }

    @Override
    public Object getRequestContext() {
      return requestContext;
    }

    @Override
    public EngineSchema getSchema() {
      return schema;
    }

    @Override
    public GlobalIDCodec getGlobalIDCodec() {
      return codec;
    }

    @Override
    public GraphQLInputObjectType getArgumentsInputType(
        String name, String containingTypeName, String fieldName) {
      return InputTypeFactory.argumentsInputType(name, containingTypeName, fieldName, schema);
    }

    @Override
    public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(
        Type<T> type, String internalId) {
      return ResolverTestBase.this.globalIDFor(type, internalId);
    }

    @Override
    public <T extends NodeCompositeOutput> String serialize(GlobalID<T> id) {
      return codec.serialize(id.getType().getName(), id.getInternalID());
    }

    @Override
    public <T extends NodeObject> String globalIDStringFor(Type<T> type, String internalId) {
      return codec.serialize(type.getName(), internalId);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String value) {
      var decoded = codec.deserialize(value);
      Type<T> type = (Type<T>) nodeTypes.get(decoded.getTypeName());
      if (type == null) {
        throw new IllegalArgumentException(
            "Register node type with globalIDFor before decoding: " + decoded.getTypeName());
      }
      return globalIDFor(type, decoded.getLocalID());
    }

    @Override
    public <T extends NodeCompositeOutput> T ref(GlobalID<T> id) {
      throw new UnsupportedOperationException("Node references require feature-app execution");
    }

    @Override
    public <T extends GraphQLObject> T ref(RootFieldCall<T> call) {
      throw new UnsupportedOperationException(
          "Root field references require feature-app execution");
    }

    @Override
    public <T> CompletableFuture<T> query(
        String selections, Map<String, Object> variables, Class<T> type) {
      return result(false, selections, variables, type);
    }

    @Override
    public <T> CompletableFuture<T> query(
        QueryFromAnnotation operation, Map<String, Object> variables, Class<T> type) {
      return result(false, operation.getOperationText(), variables, type);
    }

    @Override
    public <T> CompletableFuture<T> mutation(
        String selections, Map<String, Object> variables, Class<T> type) {
      return result(true, selections, variables, type);
    }

    @Override
    public <T> CompletableFuture<T> mutation(
        MutationFromAnnotation operation, Map<String, Object> variables, Class<T> type) {
      return result(true, operation.getOperationText(), variables, type);
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> result(
        boolean mutation, String selections, Map<String, Object> variables, Class<T> type) {
      if (mutation && !mutationAllowed) {
        throw new IllegalStateException("ctx.mutation() is only available in mutation resolvers");
      }
      Subquery key = new Subquery(mutation, selections, variables, type);
      CompletableFuture<?> configured = results.get(key);
      if (configured == null) {
        throw new IllegalArgumentException(
            "No prepared " + (mutation ? "mutation" : "query") + " result for " + key);
      }
      return (CompletableFuture<T>) configured;
    }
  }
}
