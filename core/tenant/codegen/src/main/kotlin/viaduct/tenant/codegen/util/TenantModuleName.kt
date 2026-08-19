package viaduct.tenant.codegen.util

/**
 * Derives the canonical slash-separated tenant module name from a fully-qualified tenant package.
 *
 * This is the single source of truth for tenant identity across generated tenant module configs: the
 * modern config (`TenantModuleConfigAssembler`) and the classic derived-field config
 * (`ClassicRegistryEmitter`) MUST agree on this name, because the engine groups a tenant's
 * modern `<pkg>.json` and classic `<pkg>.classic.json` sources by tenant name and bootstraps each
 * tenant exactly once. Deriving the name from anything other than the tenant package — e.g. from the
 * normalized `schemaModuleFullyQualifiedName`, which maps both `/` and `-` to `_` and cannot be
 * reversed — would let the two configs disagree for hyphenated tenants (`data/demo-todo`) or package
 * overrides, double-bootstrapping the tenant and breaking classic/modern source sharing.
 *
 * @param tenantPackage fully-qualified, dot-separated tenant package
 *   (e.g. `com.airbnb.viaduct.data.demo-todo`).
 * @param tenantPackagePrefix package prefix stripped before converting dots to slashes
 *   (e.g. `com.airbnb.viaduct`); null/blank means no prefix is stripped.
 */
fun tenantModuleNameFromPackage(
    tenantPackage: String,
    tenantPackagePrefix: String?,
): String {
    val packageName = tenantPackage.trim('.')
    val prefix = tenantPackagePrefix?.trim('.')?.takeIf(String::isNotBlank)

    val tenantModuleName = when {
        prefix == null || (packageName != prefix && !packageName.startsWith("$prefix.")) ->
            packageName.replace('.', '/')
        else -> packageName.removePrefix(prefix).removePrefix(".").replace('.', '/')
    }

    require(tenantModuleName.isNotEmpty()) {
        "Tenant module name must not be empty: tenant package '$tenantPackage' must be a subpackage of tenant package prefix '$tenantPackagePrefix'."
    }
    return tenantModuleName
}
