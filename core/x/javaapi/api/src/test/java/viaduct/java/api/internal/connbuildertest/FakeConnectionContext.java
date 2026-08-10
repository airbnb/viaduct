package viaduct.java.api.internal.connbuildertest;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import viaduct.java.api.context.ConnectionFieldExecutionContext;
import viaduct.java.api.context.ResolverExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.ResolverClassFinder;
import viaduct.java.api.reflect.RootObjectField;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.Connection;
import viaduct.java.api.types.ConnectionArguments;
import viaduct.java.api.types.GraphQLObject;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.java.api.types.NodeObject;
import viaduct.java.api.types.Query;

/**
 * Context stub implementing both {@link ConnectionFieldExecutionContext} and {@link
 * InternalContext} (as the real bridge-layer context does). {@code fromEdges} does not read any of
 * these — it only needs the context to be an {@link InternalContext} for reflective GRT
 * construction, so every operation throws.
 */
final class FakeConnectionContext extends FakeExecutionContext
    implements ConnectionFieldExecutionContext<
        Query, Query, ConnectionArguments, Connection<?, ?>> {
  @Override
  public ConnectionArguments getArguments() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Query getObjectValue() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Query getQueryValue() {
    throw new UnsupportedOperationException();
  }
}

class FakeExecutionContext implements ResolverExecutionContext, InternalContext {
  @Override
  public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String internalID) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID) {
    throw new UnsupportedOperationException();
  }

  @Override
  public @Nullable Object getRequestContext() {
    return null;
  }

  @Override
  public <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <A extends Arguments, T extends GraphQLObject> T rootFieldRef(
      RootObjectField<?, T, A> field, A arguments) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T extends NodeObject> String globalIDStringFor(Type<T> type, String internalID) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> CompletableFuture<T> query(
      String selections, Map<String, Object> variables, Class<T> targetClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> CompletableFuture<T> mutation(
      String selections, Map<String, Object> variables, Class<T> targetClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public viaduct.engine.api.ViaductSchema getSchema() {
    throw new UnsupportedOperationException();
  }

  @Override
  public graphql.schema.GraphQLInputObjectType getArgumentsInputType(
      String name, String containingTypeName, String fieldName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public viaduct.service.api.spi.GlobalIDCodec getGlobalIDCodec() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ResolverClassFinder getClassFinder() {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized) {
    throw new UnsupportedOperationException();
  }
}
