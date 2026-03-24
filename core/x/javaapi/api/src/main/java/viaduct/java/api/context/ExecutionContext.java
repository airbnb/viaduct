package viaduct.java.api.context;

import org.jspecify.annotations.Nullable;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.NodeCompositeOutput;

/** A generic context for resolvers or variable providers. */
public interface ExecutionContext {

  /** Creates a GlobalID. Example usage: globalIDFor(User.Reflection, "123") */
  <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String internalID);

  <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID);

  /**
   * Returns value set as the request context. This is typically used to pass request-scoped data
   * (like authentication info) to resolvers.
   *
   * @return The request context object, or null if none was provided
   */
  @Nullable Object getRequestContext();
}
