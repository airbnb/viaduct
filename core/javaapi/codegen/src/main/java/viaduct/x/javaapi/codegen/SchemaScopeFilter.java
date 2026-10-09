package viaduct.x.javaapi.codegen;

import java.io.File;
import java.util.List;
import java.util.Set;
import viaduct.graphql.schema.SchemaInvariantOptions;
import viaduct.graphql.schema.ViaductSchema;
import viaduct.tenant.codegen.graphql.schema.ScopeAndTenantLocalSchemaFilter;

/**
 * Applies codegen-time {@code @scope} filtering to a parsed schema, reusing the Kotlin {@link
 * ScopeAndTenantLocalSchemaFilter} rather than reimplementing scope logic in Java.
 *
 * <p>This is the Java codegen's counterpart to how the Kotlin codegen's {@code
 * SchemaObjectsBytecode} applies {@code --applied_scopes}: when a non-empty scope set is supplied,
 * the schema is projected to just the types/fields in at least one of those scopes before
 * GRT/resolver generation. When no scopes are supplied, the full (unfiltered) schema is used,
 * preserving prior behavior.
 */
final class SchemaScopeFilter {

  private SchemaScopeFilter() {}

  /**
   * Parses {@code schemaFiles} and, when {@code appliedScopes} is non-null and non-empty, returns a
   * scope-filtered projection. Otherwise returns the unfiltered schema.
   */
  static ViaductSchema parseAndFilter(
      GraphQLSchemaParser parser, List<File> schemaFiles, Set<String> appliedScopes) {
    ViaductSchema schema = parser.parse(schemaFiles);
    if (appliedScopes == null || appliedScopes.isEmpty()) {
      return schema;
    }
    // filter() has no @JvmOverloads, so its Kotlin default for schemaInvariantOptions is not
    // visible
    // to Java; pass the companion DEFAULT explicitly (accessed as
    // SchemaInvariantOptions.Companion).
    return schema.filter(
        new ScopeAndTenantLocalSchemaFilter(appliedScopes),
        SchemaInvariantOptions.Companion.getDEFAULT());
  }
}
