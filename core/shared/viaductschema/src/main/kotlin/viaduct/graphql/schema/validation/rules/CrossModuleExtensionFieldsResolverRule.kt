package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

/**
 * Validates that every field added by a cross-module `extend type` on an object type has a `@resolver`.
 *
 * When one module extends an object type defined in another module, the extending module is responsible
 * for resolving the new fields — there is no default resolver to fall back on. Fields without `@resolver`
 * in this position will cause a runtime failure when the field is queried, because no resolver is wired up.
 *
 * Same-module extensions (splitting a large type across files within one partition) are not required to
 * have resolvers, since the owning module may resolve them via a type-level `@resolver` on the base type.
 * Types defined at application level (outside any partition) are also exempt, as they have no owning module.
 *
 * @param modulePathPrefix The path segment that identifies module partition locations
 *   (e.g., `"partition/"`). Used to extract the module name from source file paths.
 */
class CrossModuleExtensionFieldsResolverRule(
    private val modulePathPrefix: String = "partition/"
) : ValidationRule(
        id = "CrossModuleExtensionFieldsResolver",
        description = "Fields added by cross-module extend type must have @resolver"
    ) {
    override fun visitObject(
        ctx: ValidationContext,
        obj: ViaductSchema.Object
    ) {
        val baseExtension = obj.extensions.firstOrNull { it.isBase } ?: return
        val baseModule = tenantFromLocation(baseExtension.sourceLocation, modulePathPrefix) ?: return

        for (ext in obj.extensions) {
            if (ext.isBase) continue
            val extModule = tenantFromLocation(ext.sourceLocation, modulePathPrefix) ?: continue
            if (extModule == baseModule) continue

            for (field in ext.members) {
                if (!field.hasAppliedDirective(RESOLVER_DIRECTIVE)) {
                    ctx.reportError(
                        code = ValidationErrorCodes.CROSS_MODULE_EXTENSION_FIELD_MISSING_RESOLVER,
                        message = "Field '${obj.name}.${field.name}' is added by module '$extModule' via " +
                            "`extend type ${obj.name}` but is missing @$RESOLVER_DIRECTIVE. " +
                            "All fields in a cross-module extension must have @$RESOLVER_DIRECTIVE.",
                        location = SchemaLocation.ofField(obj.name, field.name)
                            .withSourceLocation(field.sourceLocation)
                    )
                }
            }
        }
    }

    companion object {
        private const val RESOLVER_DIRECTIVE = "resolver"
    }
}
