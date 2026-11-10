package viaduct.tenant.codegen.util

import viaduct.graphql.schema.SchemaProvenance

fun tenantModuleNameFromPackage(
    tenantPackage: String,
    tenantPackagePrefix: String?,
): String = SchemaProvenance.moduleNameFromId(SchemaProvenance.moduleIdFromPackage(tenantPackage, tenantPackagePrefix))
