package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

/**
 * Validates that no tenant module uses `extend enum` or `extend input` to extend an input type
 * defined in a different module partition or in the application schemabase.
 *
 * Input types (enumerations and input-object types) are closed — they should be owned and extended
 * only by the module that defines them. Cross-module extension breaks modularity and complicates
 * schema hotloading.
 *
 * Same-module extensions (e.g., splitting a large enum across multiple files in one partition) are
 * allowed; only cross-module violations are reported.
 *
 * Schemabase-defined types (outside any partition) are also closed: a module may not extend an
 * input type defined at the application level.
 *
 * @param modulePathPrefix The path segment that identifies module partition locations
 *   (e.g., `"partition/"`). Used to extract the tenant name from source file paths.
 */
class NoCrossModuleInputExtensionsRule(
    private val modulePathPrefix: String = "partition/"
) : ValidationRule(
        id = "NoCrossModuleInputExtensions",
        description = "Input types (enum, input) may not be extended by a module other than the one that defines them"
    ) {
    override fun visitEnum(
        ctx: ValidationContext,
        enum: ViaductSchema.Enum
    ) {
        checkCrossModuleExtensions(ctx, enum.extensions, enum.name, "enum")
    }

    override fun visitInput(
        ctx: ValidationContext,
        input: ViaductSchema.Input
    ) {
        checkCrossModuleExtensions(ctx, input.extensions, input.name, "input")
    }

    private fun checkCrossModuleExtensions(
        ctx: ValidationContext,
        extensions: Collection<ViaductSchema.Extension<*, *>>,
        typeName: String,
        typeKeyword: String
    ) {
        val baseExtension = extensions.firstOrNull { it.isBase } ?: return
        val baseTenant = tenantFromLocation(baseExtension.sourceLocation, modulePathPrefix)

        for (ext in extensions) {
            if (ext.isBase) continue
            val extTenant = tenantFromLocation(ext.sourceLocation, modulePathPrefix) ?: continue

            if (baseTenant == null) {
                ctx.reportError(
                    code = ValidationErrorCodes.APPBASE_INPUT_EXTENSION,
                    message = "Module '$extTenant' cannot use `extend $typeKeyword $typeName` to extend a type " +
                        "defined in the application schemabase. " +
                        "Input types defined in schemabase are closed and may not be extended by tenant modules.",
                    location = SchemaLocation.ofType(typeName).withSourceLocation(ext.sourceLocation)
                )
            } else if (extTenant != baseTenant) {
                ctx.reportError(
                    code = ValidationErrorCodes.CROSS_MODULE_INPUT_EXTENSION,
                    message = "Module '$extTenant' cannot use `extend $typeKeyword $typeName` to extend a type " +
                        "defined in module '$baseTenant'. Input types must be extended only by their defining module.",
                    location = SchemaLocation.ofType(typeName).withSourceLocation(ext.sourceLocation)
                )
            }
        }
    }
}

internal fun tenantFromLocation(
    location: ViaductSchema.SourceLocation?,
    modulePathPrefix: String
): String? {
    val sourceName = location?.sourceName ?: return null
    if (!sourceName.contains(modulePathPrefix)) return null
    return sourceName.substringAfter(modulePathPrefix).substringBefore("/")
}
