
package viaduct.tenant.runtime.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.bootstrap.test.TestTenantModule

class ViaductTenantPackageFinderTest {
    @Test
    fun `set of module packages is as expected when querying built-in modules`() {
        assertEquals(
            setOf("viaduct.api.bootstrap.test"),
            TestTenantPackageFinder(listOf(TestTenantModule::class)).tenantPackages()
        )
    }

    @Test
    fun `set of module package prefixes is empty as expected when querying modules in filesystem`() {
        assertEquals(emptySet<String>(), ViaductTenantPackageFinder().tenantPackages())
    }
}
