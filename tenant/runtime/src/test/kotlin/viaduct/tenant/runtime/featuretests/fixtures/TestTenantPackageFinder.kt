package viaduct.tenant.runtime.featuretests.fixtures

import viaduct.tenant.runtime.bootstrap.TenantPackageFinder
import viaduct.tenant.runtime.bootstrap.TenantPackageInfo

class TestTenantPackageFinder(
    private val packageToResolverBases: Map<String, Set<Class<*>>>
) : TenantPackageFinder {
    override fun tenantPackages() = packageToResolverBases.keys.map { TenantPackageInfo(it) }.toSet()
}
