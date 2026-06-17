package com.example.rrs

import com.google.inject.Guice
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.remote.registry.ExecutorRegistry
import viaduct.remote.registry.SchemaRegistry

class TenantBootstrapperTest {
    @AfterEach
    fun cleanup() {
        ExecutorRegistry.clear()
        SchemaRegistry.clear()
    }

    @Test
    fun `bootstrap registers Film node resolver and publishes schema`() {
        val codeInjector = RrsCodeInjector(Guice.createInjector(StarWarsRrsModule()))
        val count = TenantBootstrapper(codeInjector).bootstrap()
        assertTrue(count > 0)
        assertTrue(SchemaRegistry.isRegistered())
        assertNotNull(ExecutorRegistry.get("Film"))
    }
}
