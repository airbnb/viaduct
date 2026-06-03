package com.example.rrs

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class RrsServerTest {
    private fun cfg() = RrsConfiguration(port = 0, callbackHost = "localhost", callbackPort = 0)

    @Test
    fun `start binds the server and stop releases it`() {
        val server = RrsServer(cfg())
        try {
            server.start()
            assertThat(server.isRunning()).isTrue()
        } finally {
            server.stop()
        }
        assertThat(server.isRunning()).isFalse()
    }

    @Test
    fun `start is idempotent`() {
        val server = RrsServer(cfg())
        try {
            server.start()
            server.start()
            assertThat(server.isRunning()).isTrue()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop before start does not throw`() {
        val server = RrsServer(cfg())
        assertThatCode { server.stop() }.doesNotThrowAnyException()
    }
}
