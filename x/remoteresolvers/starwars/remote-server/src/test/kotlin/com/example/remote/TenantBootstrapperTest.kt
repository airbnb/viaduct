package com.example.remote

import com.google.inject.Guice
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.remote.registry.NodeExecutorRegistry
import viaduct.remote.registry.SchemaRegistry

class TenantBootstrapperTest {
    @AfterEach
    fun cleanup() {
        NodeExecutorRegistry.clear()
        SchemaRegistry.clear()
    }

    @Test
    fun `bootstrap registers Film node resolver and publishes schema`() {
        val codeInjector = RemoteCodeInjector(Guice.createInjector(StarWarsRemoteModule()))
        val count = TenantBootstrapper(codeInjector).bootstrap()
        assertTrue(count > 0)
        assertTrue(SchemaRegistry.isRegistered())
        assertNotNull(NodeExecutorRegistry.get("Film"))
    }
}
