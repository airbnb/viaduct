package com.example.viadapp

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@Suppress("UNCHECKED_CAST")
class ViaductApplicationTest {
    private val originalOut = System.out
    private lateinit var outputStream: ByteArrayOutputStream

    @BeforeEach
    fun setUp() {
        outputStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outputStream))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
    }

    private fun getJsonOutput(): Map<String, Any> {
        val output = outputStream.toString().trim()
        val mapper = ObjectMapper()
        @Suppress("UNCHECKED_CAST")
        return mapper.readValue(output, Map::class.java) as Map<String, Any>
    }

    @Test
    fun testMainWithNoArguments() {
        main(emptyArray())

        val result = getJsonOutput()
        val data = result["data"] as Map<String, Any>

        assertEquals("Hello, World!", data["greeting"])
        assertNull(result["errors"])
    }

    @Test
    fun testMainWithAuthorQuery() {
        main(arrayOf("{ author }"))

        val result = getJsonOutput()
        val data = result["data"] as Map<String, Any>

        assertEquals("Brian Kernighan", data["author"])
        assertNull(result["errors"])
    }

    @Test
    fun testMainWithGreetingQuery() {
        main(arrayOf("{ greeting }"))

        val result = getJsonOutput()
        val data = result["data"] as Map<String, Any>

        assertEquals("Hello, World!", data["greeting"])
        assertNull(result["errors"])
    }

    @Test
    fun testMainWithBothFields() {
        main(
            arrayOf(
                """
            query {
                greeting
                author
            }
                """.trimIndent()
            )
        )

        val result = getJsonOutput()
        val data = result["data"] as Map<String, Any>

        assertEquals("Hello, World!", data["greeting"])
        assertEquals("Brian Kernighan", data["author"])
        assertNull(result["errors"])
    }

    @Test
    fun testMainWithInvalidField() {
        main(arrayOf("{ invalidField }"))

        val result = getJsonOutput()
        val errors = result["errors"] as List<Map<String, Any>>

        assertNull(result["data"])
        assertEquals(1, errors.size)

        val error = errors[0]
        val message = error["message"] as String
        assertTrue(message.contains("Field 'invalidField' in type 'Query' is undefined"))

        val extensions = error["extensions"] as Map<String, Any>
        assertEquals("ValidationError", extensions["classification"])
    }

    @Test
    fun testMainWithInvalidSyntax() {
        main(arrayOf("invalid syntax"))

        val result = getJsonOutput()
        val errors = result["errors"] as List<Map<String, Any>>

        assertNull(result["data"])
        assertEquals(1, errors.size)

        val error = errors[0]
        val message = error["message"] as String
        assertTrue(message.contains("Invalid syntax"))

        val extensions = error["extensions"] as Map<String, Any>
        assertEquals("InvalidSyntax", extensions["classification"])
    }

    @Test
    fun testMainWithMalformedQuery() {
        main(arrayOf("{ greeting"))

        val result = getJsonOutput()
        val errors = result["errors"] as List<Map<String, Any>>

        assertNull(result["data"])
        assertEquals(1, errors.size)

        val error = errors[0]
        val message = error["message"] as String
        assertTrue(message.contains("Invalid syntax") || message.contains("syntax"))
    }

    @Test
    fun testMainWithArgumentField() {
        main(arrayOf("""{ greet(name: "Viaduct") }"""))

        val result = getJsonOutput()
        val data = result["data"] as Map<String, Any>

        assertEquals("Hello, Viaduct!", data["greet"])
        assertNull(result["errors"])
    }

    @Test
    fun testMainWithMutation() {
        main(arrayOf("""mutation { echo(message: "hello world") }"""))

        val result = getJsonOutput()
        val data = result["data"] as Map<String, Any>

        assertEquals("hello world", data["echo"])
        assertNull(result["errors"])
    }
}
