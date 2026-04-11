package viaduct.java.runtime.example.grts;

import java.util.Map;
import viaduct.engine.api.EngineObjectData;
import viaduct.java.api.internal.JavaObjectBase;

/**
 * Test Query GRT for E2E tests.
 *
 * <p>This is a minimal Query implementation used by the test bootstrapper. The class name must
 * match the GraphQL type name "Query" for the bootstrapper to find it.
 */
public class Query extends JavaObjectBase implements viaduct.java.api.types.Query {

  public Query(EngineObjectData.Sync data) {
    super(data);
  }

  private Query(Map<String, Object> data) {
    super(data);
  }
}
