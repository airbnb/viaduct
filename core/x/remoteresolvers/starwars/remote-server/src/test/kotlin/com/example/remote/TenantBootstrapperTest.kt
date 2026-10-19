package com.example.remote

import com.google.inject.Guice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.tenantloading.ExecutionRegistryConfigSourceCollector

class TenantBootstrapperTest {
    @Test
    fun `packaged registry index contains both demo tenants`() {
        val sources = ExecutionRegistryConfigSourceCollector.fromResources()

        assertEquals(setOf("filmography", "universe"), sources.map { it.tenantName }.toSet())
    }

    @Test
    fun `bootstrap builds Film node resolver and schema`() {
        val codeInjector = RemoteCodeInjector(Guice.createInjector(StarWarsRemoteModule()))
        val runtime = TenantBootstrapper(codeInjector).bootstrap()
        assertTrue(runtime.nodeExecutors.isNotEmpty())
        assertNotNull(runtime.schema.schema.getObjectType("Film"))
        assertNotNull(runtime.schema.schema.getObjectType("Species"))
        assertNotNull(runtime.nodeExecutors["Film"])
    }
}
