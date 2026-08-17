package viaduct.java.runtime.example.grts;

import viaduct.engine.api.EngineObjectData;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.ObjectBase;

/**
 * Test Query GRT for E2E tests.
 *
 * <p>This is a minimal Query implementation used by the test bootstrapper. The class name must
 * match the GraphQL type name "Query" for the bootstrapper to find it.
 */
public class Query extends ObjectBase implements viaduct.java.api.types.Query {

  public Query(InternalContext context, EngineObjectData.Sync data) {
    super(context, data);
  }
}
