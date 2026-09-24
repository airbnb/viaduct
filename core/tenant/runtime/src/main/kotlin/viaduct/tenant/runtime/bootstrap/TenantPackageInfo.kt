package viaduct.tenant.runtime.bootstrap

import viaduct.engine.api.TenantModuleMetadata

data class TenantPackageInfo(
    val packageName: String,
    val metadata: TenantModuleMetadata = TenantModuleMetadata.EMPTY,
)
