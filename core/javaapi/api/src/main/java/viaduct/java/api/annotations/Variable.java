package viaduct.java.api.annotations;

/**
 * Defines a variable binding for use in resolver fragments.
 *
 * <p>This is the Java equivalent of Kotlin's {@code @Variable} annotation. Variables extract values
 * from the execution environment (arguments, parent object fields, or query fields) and bind them
 * to GraphQL variables used in fragment selection sets.
 *
 * <h2>Usage</h2>
 *
 * <p>Variables are defined in the {@link Resolver#variables()} array:
 *
 * <pre>{@code
 * @Resolver(
 *   objectValueFragment = """
 *     fragment _ on User {
 *       posts(limit: $postLimit) {
 *         id
 *         title
 *       }
 *     }
 *     """,
 *   variables = {
 *     @Variable(name = "postLimit", fromArgument = "limit")
 *   }
 * )
 * }</pre>
 *
 * <h2>Variable Sources</h2>
 *
 * <h3>From Arguments</h3>
 *
 * <p>Extract from the current field's arguments:
 *
 * <pre>{@code
 * @Variable(name = "limit", fromArgument = "maxResults")
 * }</pre>
 *
 * <p>Supports dot-separated paths for nested input object fields:
 *
 * <pre>{@code
 * @Variable(name = "userId", fromArgument = "filters.userId")
 * }</pre>
 *
 * <h3>From Object Fields</h3>
 *
 * <p>Extract from parent object selections:
 *
 * <pre>{@code
 * @Variable(name = "authorId", fromObjectField = "author.id")
 * }</pre>
 *
 * <h3>From Query Fields</h3>
 *
 * <p>Extract from Query root selections:
 *
 * <pre>{@code
 * @Variable(name = "currentUserId", fromQueryField = "currentUser.id")
 * }</pre>
 *
 * <h2>Constraints</h2>
 *
 * <ul>
 *   <li>Exactly one of {@code fromArgument}, {@code fromObjectField}, or {@code fromQueryField}
 *       must be specified
 *   <li>Paths must terminate on scalar or enum values (or nullable/list wrappers thereof)
 *   <li>Paths cannot traverse through list-valued fields
 *   <li>The type at the path end must be compatible with where the variable is used
 *   <li>Non-nullable variable locations require non-nullable paths (no nullable traversals)
 * </ul>
 */
public @interface Variable {

  /**
   * The name of the GraphQL variable being defined.
   *
   * @return the variable name (e.g., "postLimit", "userId")
   */
  String name();

  /**
   * Path to a field in the current field's arguments.
   *
   * <p>Can be a simple field name or a dot-separated path for nested input objects.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code "limit"} - extracts the {@code limit} argument
   *   <li>{@code "filters.userId"} - extracts {@code userId} from the {@code filters} input object
   * </ul>
   *
   * @return the argument path, or empty string if not using this source
   */
  String fromArgument() default "";

  /**
   * Path to a field in the parent object's selection set.
   *
   * <p>The path must exist in the {@link Resolver#objectValueFragment()}.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code "id"} - extracts the {@code id} field
   *   <li>{@code "author.id"} - extracts {@code id} from the {@code author} field
   * </ul>
   *
   * @return the object field path, or empty string if not using this source
   */
  String fromObjectField() default "";

  /**
   * Path to a field in the Query root's selection set.
   *
   * <p>The path must exist in the {@link Resolver#queryValueFragment()}.
   *
   * <p>Example: {@code "currentUser.id"}
   *
   * @return the query field path, or empty string if not using this source
   */
  String fromQueryField() default "";
}
