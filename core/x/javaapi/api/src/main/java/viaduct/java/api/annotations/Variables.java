package viaduct.java.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the names and GraphQL types of the variables produced by a {@link
 * viaduct.java.api.variables.VariablesProvider} implementation.
 *
 * <p>Java equivalent of Kotlin's {@code @Variables} annotation. Apply to a nested static class that
 * implements {@code VariablesProvider} inside a resolver class.
 *
 * <p>Each entry has the form {@code "name: Type"} (whitespace ignored) where {@code Type} is a
 * GraphQL type expression matching the variable's usage in the fragment.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Resolver(objectValueFragment = "fragment _ on Query { intermediary(arg: $x) }")
 * public static class FromVariablesProviderResolver extends QueryResolvers.FromVariablesProvider {
 *   @Override
 *   public CompletableFuture<Integer> resolve(Context ctx) { ... }
 *
 *   @Variables(types = "x: Int!")
 *   public static class TestVariablesProvider implements VariablesProvider<Arguments.None> {
 *     @Override
 *     public CompletableFuture<Map<String, Object>> provide(
 *         VariablesProviderContext<Arguments.None> ctx) {
 *       return CompletableFuture.completedFuture(Map.of("x", 123));
 *     }
 *   }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Variables {

  /**
   * Variable declarations in {@code "name: Type"} form. Whitespace is ignored. The set of variable
   * names declared here must exactly match the keys returned by {@link
   * viaduct.java.api.variables.VariablesProvider#provide}.
   *
   * @return the variable declarations
   */
  String[] types();
}
