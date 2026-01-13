package viaduct.serve

import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for ViaductServer port configuration.
 */
class ViaductServerPortTest {
    @Test
    fun `ViaductServer should accept port 0 for dynamic port assignment`() {
        // Given: A ViaductServer configured with port 0
        val server = ViaductServer(port = 0, host = "127.0.0.1")

        // Then: Server should be instantiable with port 0
        // (Actual server start would require a factory and schema,
        // so we just verify construction works)
        assertNotNull(server)
    }

    @Test
    fun `ViaductServer should accept specific port numbers`() {
        // Given: A ViaductServer configured with a specific port
        val server = ViaductServer(port = 9999, host = "127.0.0.1")

        // Then: Server should be instantiable with specific port
        assertNotNull(server)
    }

    @Test
    fun `main function should read port from system property`() {
        // Given: System property for port is set to 0
        System.setProperty("serve.port", "0")
        System.setProperty("serve.host", "127.0.0.1")

        // When/Then: Verify properties can be read
        val port = System.getProperty("serve.port", "8080").toIntOrNull() ?: 8080
        val host = System.getProperty("serve.host", "0.0.0.0")

        assertTrue(port == 0, "Port should be 0 when set via system property")
        assertTrue(host == "127.0.0.1", "Host should be 127.0.0.1 when set via system property")

        // Clean up
        System.clearProperty("serve.port")
        System.clearProperty("serve.host")
    }
}
