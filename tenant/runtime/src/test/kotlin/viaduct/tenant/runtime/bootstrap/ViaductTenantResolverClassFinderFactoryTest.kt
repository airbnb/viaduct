package viaduct.tenant.runtime.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.TenantModuleMetadata

class ViaductTenantResolverClassFinderFactoryTest {
    private val metadata = TenantModuleMetadata(name = "test-tenant")

    @Test
    fun `create(TenantPackageInfo) forwards metadata to finder`() {
        val finder = ViaductTenantResolverClassFinderFactory(grtPackagePrefix = "some.grts")
            .create(TenantPackageInfo("some.package", metadata))
        assertEquals(metadata, finder.tenantModuleMetadata())
    }

    @Test
    fun `create with packageName only defaults to empty metadata`() {
        val finder = ViaductTenantResolverClassFinderFactory(grtPackagePrefix = "some.grts")
            .create("some.package")
        assertEquals(TenantModuleMetadata.EMPTY, finder.tenantModuleMetadata())
    }

    @Test
    fun `default constructor creates factory that forwards metadata correctly`() {
        val finder = ViaductTenantResolverClassFinderFactory()
            .create(TenantPackageInfo("some.package", metadata))
        assertEquals(metadata, finder.tenantModuleMetadata())
    }
}
