package viaduct.java.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation used to mark generated resolver base classes in the Java API.
 *
 * <p>This is the Java equivalent of Kotlin's {@code @ResolverFor} annotation. It indicates which
 * GraphQL type and field this resolver base class corresponds to.
 *
 * <p>Code generators will apply this annotation to generated abstract base classes. Tenant
 * developers then extend these bases and annotate their concrete implementations with {@link
 * Resolver}.
 *
 * <p>Example generated code:
 *
 * <pre>{@code
 * @ResolverFor(typeName = "Query", fieldName = "user")
 * public abstract class QueryUserResolverBase
 *     implements FieldResolverBase<User, Query, QueryUserArguments, UserSelections> {
 *   // Generated methods
 * }
 * }</pre>
 *
 * <p>Tenant implementation:
 *
 * <pre>{@code
 * @Resolver
 * public class QueryUserResolver extends QueryUserResolverBase {
 *   @Override
 *   public CompletableFuture<User> resolve(Context ctx) {
 *     // Implementation
 *   }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResolverFor {

  /**
   * The GraphQL type name that contains the field being resolved.
   *
   * @return the type name (e.g., "Query", "User", "Product")
   */
  String typeName();

  /**
   * The GraphQL field name being resolved.
   *
   * @return the field name (e.g., "user", "name", "price")
   */
  String fieldName();
}
