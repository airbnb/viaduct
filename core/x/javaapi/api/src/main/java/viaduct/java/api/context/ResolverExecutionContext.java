package viaduct.java.api.context;

import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.types.NodeCompositeOutput;

/**
 * A generic context for resolving fields or types. This interface will be extended with
 * query/mutation methods in future iterations.
 */
public interface ResolverExecutionContext extends ExecutionContext {
  // TODO: Add query() and mutation() methods for sub-operations
  // TODO: Add selectionsFor() method for creating selection sets

  <T extends NodeCompositeOutput> T nodeFor(GlobalID<T> id);
  // TODO: Add globalIDStringFor() method for creating global ID strings
}
