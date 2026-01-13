@file:Suppress("ForbiddenImport")

package viaduct.engine.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper

class TenantAPIBootstrapperTest {
    @Test
    fun `test flatten function`(): Unit =
        runBlocking {
            // Create mock TenantModuleBootstrapper instances
            val tenantModuleBootstrapper1 = MockTenantModuleBootstrapper(MockSchema.minimal)
            val tenantModuleBootstrapper2 = MockTenantModuleBootstrapper(MockSchema.minimal)
            val tenantModuleBootstrapper3 = MockTenantModuleBootstrapper(MockSchema.minimal)

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
