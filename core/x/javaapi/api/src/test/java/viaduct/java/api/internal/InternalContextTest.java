package viaduct.java.api.internal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.NodeCompositeOutput;

/** Unit tests for {@link InternalContext#from(ExecutionContext)}. */
class InternalContextTest {

  /** Implements both ExecutionContext and InternalContext, like the runtime context impls. */
  static final class CombinedContext implements ExecutionContext, InternalContext {
    @Override
    public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(
        Type<T> type, String internalID) {
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

  /** An ExecutionContext that does NOT implement InternalContext. */
  static final class PlainContext implements ExecutionContext {
    @Override
    public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(
        Type<T> type, String internalID) {
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
  }

  @Test
  void from_returnsSameInstanceWhenContextImplementsInternalContext() {
    CombinedContext ctx = new CombinedContext();

    assertSame(ctx, InternalContext.from(ctx));
  }

  @Test
  void from_throwsWhenContextDoesNotImplementInternalContext() {
    PlainContext ctx = new PlainContext();

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> InternalContext.from(ctx));
    assertTrue(e.getMessage().contains("does not implement InternalContext"));
  }
}
