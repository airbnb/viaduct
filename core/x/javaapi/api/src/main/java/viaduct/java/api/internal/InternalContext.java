package viaduct.java.api.internal;

import viaduct.engine.api.ViaductSchema;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.service.api.spi.GlobalIDCodec;

/**
 * Encapsulates contextual dependencies of the Viaduct runtime that we don't want to expose to
 * tenants.
 *
 * <p>This is the Java mirror of Kotlin's {@code viaduct.api.internal.InternalContext}. Like its
 * Kotlin counterpart it is an API-layer interface whose implementation lives in the runtime layer
 * ({@code viaduct.java.runtime.bridge.InternalContextImpl}).
 *
 * <p>An {@code InternalContext} is attached to object and input GRTs (Generated Runtime Types) via
 * their constructors and propagated to nested GRTs, so the GRTs can access shared runtime
 * dependencies without those dependencies leaking into the tenant-facing API.
 *
 * <p>The Kotlin interface additionally exposes a {@code grtConvFactory} and a {@code
 * deserializeGlobalID} method; both are intentionally omitted here. The former maps between GRT and
 * IR values (a Kotlin-only concern — Java GRTs wrap engine data directly), and the latter is
 * GlobalID decoding that is out of scope for this context.
 */
public interface InternalContext {

  /**
   * Casts an {@link ExecutionContext} to {@link InternalContext}. Like Kotlin's {@code ctx as
   * InternalContext} — the runtime context implementations (e.g. {@code
   * SimpleFieldExecutionContext} in the bridge layer) implement both interfaces.
   *
   * @throws IllegalArgumentException if the context does not implement InternalContext
   */
  static InternalContext from(ExecutionContext ctx) {
    if (ctx instanceof InternalContext ic) {
      return ic;
    }
    throw new IllegalArgumentException(
        "ExecutionContext does not implement InternalContext: " + ctx.getClass().getName());
  }

  /** The Viaduct schema that underpins GRTs. */
  ViaductSchema getSchema();

  /**
   * The codec used to translate between {@link viaduct.java.api.globalid.GlobalID} tenant-space
   * values and {@link String} engine-space values. This is the service-level codec shared across
   * all tenant modules in a Viaduct instance.
   */
  GlobalIDCodec getGlobalIDCodec();

  /**
   * Resolves GRT classes and type information by GraphQL type name. Java analog of Kotlin's {@code
   * ReflectionLoader}.
   */
  ResolverClassFinder getClassFinder();

  /**
   * Deserializes a GlobalID string into a typed {@link GlobalID} instance.
   *
   * <p>Mirrors Kotlin's {@code InternalContext.deserializeGlobalID()}. Decodes the serialized
   * representation (typically Base64-encoded "typeName:localId") into a {@link GlobalID} carrying
   * both the resolved type and the internal identifier.
   *
   * @param serialized the serialized GlobalID string from the GraphQL response
   * @param <T> the node type the GlobalID refers to
   * @return the deserialized typed GlobalID
   */
  <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized);
}
