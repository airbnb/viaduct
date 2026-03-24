package viaduct.java.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation used to mark generated node resolver base classes in the Java API.
 *
 * <p>This is the Java equivalent of Kotlin's {@code @NodeResolverFor} annotation. It indicates
 * which GraphQL Node type this resolver base class corresponds to.
 *
 * <p>Node resolvers handle resolution of types implementing the GraphQL Node interface, which
 * requires a globally unique {@code id} field. These resolvers fetch node data by global ID.
 *
 * <p>Example generated code:
 *
 * <pre>{@code
 * @NodeResolverFor(typeName = "User")
 * public abstract class UserNodeResolverBase
 *     implements NodeResolverBase<User> {
 *   // Generated methods
 * }
 * }</pre>
 *
 * <p>Tenant implementation:
 *
 * <pre>{@code
 * @Resolver
 * public class UserNodeResolver extends UserNodeResolverBase {
 *   @Override
 *   public CompletableFuture<User> resolve(NodeContext<User> ctx) {
 *     String internalId = ctx.getId().getInternalID();
 *     return userService.fetchById(internalId);
 *   }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface NodeResolverFor {

  /**
   * The GraphQL type name that implements the Node interface.
   *
   * @return the type name (e.g., "User", "Product", "Order")
   */
  String typeName();
}
