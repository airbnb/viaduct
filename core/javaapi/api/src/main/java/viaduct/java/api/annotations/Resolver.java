package viaduct.java.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark a class as a resolver implementation for a field in a GraphQL schema.
 *
 * <p>This is the Java equivalent of Kotlin's {@code @Resolver} annotation. Applied to concrete
 * resolver classes that extend generated resolver base classes (marked with {@link ResolverFor}).
 *
 * <p>Resolvers define custom resolution logic for fields that require runtime computation, database
 * lookups, or complex business logic, rather than simple property access.
 *
 * <h2>Required Selection Sets</h2>
 *
 * <p>Resolvers can declare required fields from the parent object or query root using fragments:
 *
 * <h3>Object Value Fragment</h3>
 *
 * <p>Specifies fields needed from the parent object:
 *
 * <pre>{@code
 * @Resolver(
 *   objectValueFragment = """
 *     fragment _ on User {
 *       firstName
 *       lastName
 *     }
 *     """
 * )
 * public class UserDisplayNameResolver extends UserDisplayNameResolverBase {
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
 * <h3>Query Value Fragment</h3>
 *
 * <p>Specifies fields needed from the Query root:
 *
 * <pre>{@code
 * @Resolver(
 *   queryValueFragment = """
 *     fragment _ on Query {
 *       currentUser {
 *         id
 *       }
 *     }
 *     """
 * )
 * }</pre>
 *
 * <h3>Shorthand Syntax</h3>
 *
 * <p>For single-field requirements, use shorthand:
 *
 * <pre>{@code
 * @Resolver(objectValueFragment = "displayName")
 * }</pre>
 *
 * <h2>Batch Resolvers</h2>
 *
 * <p>For efficient processing of multiple field requests (preventing N+1 queries), implement {@code
 * batchResolve} instead of {@code resolve}:
 *
 * <pre>{@code
 * @Resolver(objectValueFragment = "fragment _ on Character { id }")
 * public class CharacterHomeworldResolver extends CharacterHomeworldResolverBase {
 *   @Override
 *   public CompletableFuture<Map<Context, Planet>> batchResolve(List<Context> contexts) {
 *     List<String> characterIds = contexts.stream()
 *       .map(ctx -> ctx.getObjectValue().getId())
 *       .toList();
 *     return planetService.fetchByCharacterIds(characterIds)
 *       .thenApply(planets -> matchContextsToPlanets(contexts, planets));
 *   }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Resolver {

  /**
   * GraphQL fragment describing the selection set required from the parent object.
   *
   * <p>Can be either:
   *
   * <ul>
   *   <li>A full fragment: {@code "fragment _ on TypeName { field1 field2 }"}
   *   <li>Shorthand for a single field: {@code "fieldName"}
   *   <li>Empty string (default) if no object fields are needed
   * </ul>
   *
   * @return the object value fragment
   */
  String objectValueFragment() default "";

  /**
   * GraphQL fragment describing the selection set required from the Query root.
   *
   * <p>Format: {@code "fragment _ on Query { field1 field2 }"}
   *
   * @return the query value fragment
   */
  String queryValueFragment() default "";

  /**
   * Variable bindings for extracting values from the execution environment and passing them to
   * fragments.
   *
   * <p>See {@link Variable} for details.
   *
   * @return array of variable definitions
   */
  Variable[] variables() default {};
}
