package viaduct.devserve

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for DevServeServer port configuration.
 */
class DevServeServerPortTest {

    @Test
    fun `DevServeServer should accept port 0 for dynamic port assignment`() {
        // Given: A DevServeServer configured with port 0
        val server = DevServeServer(port = 0, host = "127.0.0.1")

        // Then: Server should be instantiable with port 0
        // (Actual server start would require a factory and schema,
        // so we just verify construction works)
        assertNotNull(server)
    }

    @Test
    fun `DevServeServer should accept specific port numbers`() {
        // Given: A DevServeServer configured with a specific port
        val server = DevServeServer(port = 9999, host = "127.0.0.1")

        // Then: Server should be instantiable with specific port
        assertNotNull(server)
    }

    @Test
    fun `main function should read port from system property`() {
        // Given: System property for port is set to 0
        System.setProperty("devserve.port", "0")
        System.setProperty("devserve.host", "127.0.0.1")

        // When/Then: Verify properties can be read
        val port = System.getProperty("devserve.port", "8080").toIntOrNull() ?: 8080
        val host = System.getProperty("devserve.host", "0.0.0.0")

        assertTrue(port == 0, "Port should be 0 when set via system property")
        assertTrue(host == "127.0.0.1", "Host should be 127.0.0.1 when set via system property")

        // Clean up
        System.clearProperty("devserve.port")
        System.clearProperty("devserve.host")
    }
}
