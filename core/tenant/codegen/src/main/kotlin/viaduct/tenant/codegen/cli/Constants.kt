package viaduct.tenant.codegen.cli

import viaduct.graphql.schema.SchemaProvenance
import viaduct.utils.resources.ViaductResourcePaths

internal fun registryResourcePath(
    tenantPackage: String,
    prefix: String?,
    generatorPluginId: String
): String =
    ViaductResourcePaths.manifest(
        SchemaProvenance.moduleIdFromPackage(tenantPackage, prefix),
        generatorPluginId,
        "$tenantPackage.json",
    )
