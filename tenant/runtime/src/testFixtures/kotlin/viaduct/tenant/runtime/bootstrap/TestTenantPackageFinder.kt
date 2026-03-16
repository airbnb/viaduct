package viaduct.tenant.runtime.bootstrap

import kotlin.reflect.KClass
import viaduct.api.TenantModule

/**
 * A mock implementation of the TenantPackageFinder interface for testing.
 */
class TestTenantPackageFinder(classes: Iterable<KClass<out TenantModule>>) : TenantPackageFinder {
    private val packageInfos = classes.map { TenantPackageInfo(packageName = it.java.packageName) }.toSet()

    override fun tenantPackages() = packageInfos
}
