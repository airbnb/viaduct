
package viaduct.tenant.runtime.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.bootstrap.test.TestTenantModule
import viaduct.engine.api.TenantModuleMetadata

class ViaductTenantPackageFinderTest {
    @Test
    fun `set of module packages is as expected when querying built-in modules`() {
        assertEquals(
            setOf(TenantPackageInfo("viaduct.api.bootstrap.test")),
            TestTenantPackageFinder(listOf(TestTenantModule::class)).tenantPackages()
        )
    }

    @Test
    fun `set of module package prefixes is empty as expected when querying modules in filesystem`() {
        assertEquals(emptySet<TenantPackageInfo>(), ViaductTenantPackageFinder().tenantPackages())
    }

    @Test
    fun `metadata is populated from module metadata map`() {
        val module = TestTenantModule()
        val packageInfo = TenantPackageInfo(
            packageName = TestTenantModule::class.java.packageName,
            metadata = TenantModuleMetadata.fromMap(module.metadata),
        )

        assertEquals(TenantModuleMetadata(name = "TestModule"), packageInfo.metadata)
    }
}
