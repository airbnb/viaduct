package com.example.remote

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteServerTest {
    private fun cfg() = RemoteConfiguration(port = 0, callbackHost = "localhost", callbackPort = 0)

    @Test
    fun `start binds the server and stop releases it`() {
        val server = RemoteServer(cfg())
        try {
            server.start()
            assertTrue(server.isRunning())
        } finally {
            server.stop()
        }
        assertFalse(server.isRunning())
    }

    @Test
    fun `start is idempotent`() {
        val server = RemoteServer(cfg())
        try {
            server.start()
            server.start()
            assertTrue(server.isRunning())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop before start does not throw`() {
        val server = RemoteServer(cfg())
        server.stop()
    }
}
