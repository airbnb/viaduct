package viaduct.java.api.resolvers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.context.FieldExecutionContext;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.CompositeOutput;
import viaduct.java.api.types.GraphQLObject;
import viaduct.java.api.types.Query;

/**
 * Base interface for field resolver implementations in the Java Tenant API.
 *
 * <p>This is the Java equivalent of Kotlin's {@code ResolverBase<T>}. Generated resolver base
 * classes implement this interface, and tenant developers extend those generated classes.
 *
 * <p>Resolvers can implement either:
 *
 * <ul>
 *   <li>{@code resolve(Context)} for single-item resolution
 *   <li>{@code batchResolve(List)} for batch resolution (more efficient)
 * </ul>
 *
 * <p>Implementing both methods is not allowed - choose one based on your use case.
 *
 * <h2>Single Resolution</h2>
 *
 * <p>Use for simple fields where each resolution is independent:
 *
 * <pre>{@code
 * @JavaResolver
 * public class UserNameResolver extends UserNameResolverBase {
 *   @Override
 *   public CompletableFuture<String> resolve(Context ctx) {
 *     User user = ctx.getObjectValue();
 *     return CompletableFuture.completedFuture(
 *       user.getFirstName() + " " + user.getLastName()
 *     );
 *   }
 * }
 * }</pre>
 *
 * <h2>Batch Resolution</h2>
 *
 * <p>Use for fields that benefit from batching (e.g., database lookups) to prevent N+1 queries:
 *
 * <pre>{@code
 * @JavaResolver
 * public class CharacterHomeworldResolver extends CharacterHomeworldResolverBase {
 *   @Inject CharacterService characterService;
 *
 *   @Override
 *   public CompletableFuture<Map<Context, Planet>> batchResolve(List<Context> contexts) {
 *     List<String> characterIds = contexts.stream()
 *       .map(ctx -> ctx.getObjectValue().getId())
 *       .toList();
 *
 *     return characterService.fetchHomeworldsByCharacterIds(characterIds)
 *       .thenApply(homeworlds -> matchContextsToHomeworlds(contexts, homeworlds));
 *   }
 * }
 * }</pre>
 *
 * @param <T> the return type of the resolver (the field's GraphQL type)
 * @param <O> the parent object type containing this field
 * @param <Q> the Query root type
 * @param <A> the arguments type for this field
 * @param <S> the selections type for the output
 */
public interface FieldResolverBase<
    T, O extends GraphQLObject, Q extends Query, A extends Arguments, S extends CompositeOutput> {

  /**
   * Context type alias for this resolver, providing type-safe access to object value, query value,
   * arguments, and selections.
   */
  interface Context<
          O extends GraphQLObject, Q extends Query, A extends Arguments, S extends CompositeOutput>
      extends FieldExecutionContext<O, Q, A, S> {}

  /**
   * Resolves the field value for a single parent object.
   *
   * <p>The engine calls this method once per parent object when the field is requested. Use this
   * for simple fields where resolution logic doesn't benefit from batching.
   *
   * <p><strong>Note:</strong> Do not implement both {@code resolve} and {@code batchResolve}.
   * Choose one based on your use case.
   *
   * @param ctx the execution context containing object value, query value, arguments, and
   *     selections
   * @return a future that completes with the resolved field value
   */
  default CompletableFuture<T> resolve(Context<O, Q, A, S> ctx) {
    throw new UnsupportedOperationException(
        "Resolver must implement either resolve() or batchResolve(), but not both");
  }

  /**
   * Resolves field values for multiple parent objects in a single batch.
   *
   * <p>The engine calls this method with a batch of parent objects, allowing efficient resolution
   * that can combine multiple requests (e.g., single database query instead of N queries).
   *
   * <p>The returned map must contain an entry for each input context. The engine matches results to
   * requests using the map keys.
   *
   * <p><strong>Note:</strong> Do not implement both {@code resolve} and {@code batchResolve}.
   * Choose one based on your use case.
   *
   * @param contexts list of execution contexts, one per parent object
   * @return a future that completes with a map from context to resolved value
   */
  default CompletableFuture<Map<Context<O, Q, A, S>, T>> batchResolve(
      List<Context<O, Q, A, S>> contexts) {
    throw new UnsupportedOperationException(
        "Resolver must implement either resolve() or batchResolve(), but not both");
  }
}
