package com.example.remote

import com.google.inject.Guice
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TenantBootstrapperTest {
    @Test
    fun `bootstrap builds Film node resolver and schema`() {
        val codeInjector = RemoteCodeInjector(Guice.createInjector(StarWarsRemoteModule()))
        val runtime = TenantBootstrapper(codeInjector).bootstrap()
        assertTrue(runtime.nodeExecutors.isNotEmpty())
        assertNotNull(runtime.schema.schema.getObjectType("Film"))
        assertNotNull(runtime.nodeExecutors["Film"])
    }
}
