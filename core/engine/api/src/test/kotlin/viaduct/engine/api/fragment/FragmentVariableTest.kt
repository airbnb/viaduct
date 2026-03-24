package viaduct.engine.api.fragment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FragmentVariableTest {
    enum class TestEnum {
        TEST_VALUE
    }

    @Test
    fun testNumber() {
        val arg = FragmentVariable("someNumberArg", 123)
        assertEquals("\$someNumberArg", arg.toString())
        assertEquals("123", arg.value.toString())
    }

    @Test
    fun testString() {
        val arg = FragmentVariable("someArg", "my string")
        assertEquals("\$someArg", arg.toString())
        assertEquals("my string", arg.value)
    }

    @Test
    fun testEnum() {
        val arg = FragmentVariable("someEnumArg", TestEnum.TEST_VALUE)
        assertEquals("\$someEnumArg", arg.toString())
        assertEquals("TEST_VALUE", arg.value.toString())
    }

    @Test
    fun testValidateEnum() {
        val arg = FragmentVariable("someEnumArg", "TEST_VALUE").validateEnum<TestEnum>()
        assertEquals("\$someEnumArg", arg.toString())
        assertEquals("TEST_VALUE", arg.value.toString())
    }

    @Test
    fun testValidateEnumNull() {
        val arg = FragmentVariable("someEnumArg", null).validateEnum<TestEnum>()
        assertEquals("\$someEnumArg", arg.toString())
        assertEquals("null", arg.value.toString())
    }

    @Test
    fun testValidateEnumInvalidString() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            FragmentVariable("someEnumArg", "wrong enum").validateEnum<TestEnum>()
        }

        assertTrue(
            exception.message?.contains(
                "No enum constant viaduct.engine.api.fragment.FragmentVariableTest.TestEnum.wrong enum"
            ) == true
        )
    }
}
