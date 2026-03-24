package viaduct.java.api.schema;

import org.jspecify.annotations.Nullable;

/**
 * Java wrapper around the compiled Viaduct GraphQL schema.
 *
 * <p>This is the Java equivalent of Kotlin's {@code ViaductSchema}. The actual implementation will
 * be provided by the bridge layer and will wrap the underlying Kotlin schema and GraphQL-Java
 * schema.
 *
 * <p>The schema provides access to type information, field definitions, and validation rules that
 * are used during resolver discovery and execution.
 *
 * <p><strong>Future implementation:</strong> This interface will provide:
 *
 * <ul>
 *   <li>Type lookup by name
 *   <li>Field definition access
 *   <li>Interface and union membership queries
 *   <li>Directive inspection
 * </ul>
 *
 * <h2>Usage in Bootstrapper</h2>
 *
 * <p>The bootstrapper receives the schema to validate resolver registrations:
 *
 * <pre>{@code
 * public Iterable<FieldResolverEntry> fieldResolverExecutors(JavaViaductSchema schema) {
 *   List<FieldResolverEntry> entries = new ArrayList<>();
 *   for (Class<?> resolverClass : findResolverClasses()) {
 *     JavaResolverFor annotation = resolverClass.getAnnotation(JavaResolverFor.class);
 *     // Validate against schema
 *     if (schema.hasField(annotation.typeName(), annotation.fieldName())) {
 *       entries.add(createExecutor(resolverClass, annotation));
 *     }
 *   }
 *   return entries;
 * }
 * }</pre>
 */
public interface JavaViaductSchema {

  /**
   * Checks if the schema contains a type with the given name.
   *
   * @param typeName the GraphQL type name
   * @return true if the type exists in the schema
   */
  boolean hasType(String typeName);

  /**
   * Checks if the given type contains a field with the given name.
   *
   * @param typeName the GraphQL type name
   * @param fieldName the field name
   * @return true if the type exists and has the field
   */
  boolean hasField(String typeName, String fieldName);

  /**
   * Gets the kind of a type (object, interface, union, enum, scalar, input).
   *
   * @param typeName the GraphQL type name
   * @return the type kind, or null if the type doesn't exist
   */
  @Nullable String getTypeKind(String typeName);

  /**
   * Checks if the given type implements the Node interface.
   *
   * @param typeName the GraphQL type name
   * @return true if the type implements Node
   */
  boolean isNodeType(String typeName);
}
