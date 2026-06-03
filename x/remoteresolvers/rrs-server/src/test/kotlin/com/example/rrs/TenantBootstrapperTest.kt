package com.example.rrs

import com.google.inject.Guice
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
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
        assertThat(count).isGreaterThan(0)
        assertThat(SchemaRegistry.isRegistered()).isTrue()
        assertThat(ExecutorRegistry.get("Film")).isNotNull()
    }
}
