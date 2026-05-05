@file:Suppress("ForbiddenImport")

package viaduct.engine.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockLegacyTenantModuleBootstrapper
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper
import viaduct.engine.api.spi.flatten

class TenantAPIBootstrapperTest {
    @Test
    fun `test flatten function`(): Unit =
        runBlocking {
            // Create mock LegacyTenantModuleBootstrapper instances
            val tenantModuleBootstrapper1 = MockLegacyTenantModuleBootstrapper(MockSchema.minimal)
            val tenantModuleBootstrapper2 = MockLegacyTenantModuleBootstrapper(MockSchema.minimal)
            val tenantModuleBootstrapper3 = MockLegacyTenantModuleBootstrapper(MockSchema.minimal)

            // Create MockTenantAPIBootstrapper instances
            val tenantAPIBootstrapper1 = MockTenantAPIBootstrapper(listOf(tenantModuleBootstrapper1, tenantModuleBootstrapper2))
            val tenantAPIBootstrapper2 = MockTenantAPIBootstrapper(listOf(tenantModuleBootstrapper3))

            // Create a list of TenantAPIBootstrapper instances and flatten them
            val flattenedBootstrapper = listOf(tenantAPIBootstrapper1, tenantAPIBootstrapper2).flatten()

            // Verify the result
            val result = flattenedBootstrapper.tenantModuleBootstrappers().toList()
            assertEquals(3, result.size)
            assertEquals(listOf(tenantModuleBootstrapper1, tenantModuleBootstrapper2, tenantModuleBootstrapper3), result)
        }
}
