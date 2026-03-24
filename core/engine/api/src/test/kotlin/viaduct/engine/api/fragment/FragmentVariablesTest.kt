package viaduct.engine.api.fragment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FragmentVariablesTest {
    enum class TestEnum {
        TEST_VALUE
    }

    @Test
    fun testEmpty() {
        val testSubject = FragmentVariables.EMPTY

        val arg: FragmentVariable<Int?> by testSubject
        val arg2: FragmentVariable<String?> by testSubject
        val arg3 = testSubject.getEnum<TestEnum>("arg3")

        assertEquals("null", arg.value.toString())
        assertEquals("null", arg2.value.toString())
        assertEquals("null", arg3.value.toString())
    }

    @Test
    fun testEnumFromEnum() {
        val testSubject = FragmentVariables.fromMap(mapOf("arg" to TestEnum.TEST_VALUE))
        val arg = testSubject.getEnum<TestEnum>("arg")
        assertEquals("TEST_VALUE", arg.value.toString())
    }

    @Test
    fun testEnumWithDefaultValue() {
        val testSubject = FragmentVariables.EMPTY
        val arg = testSubject.getEnum("arg", TestEnum.TEST_VALUE)
        assertEquals("TEST_VALUE", arg.value.toString())
    }

    @Test
    fun testEnumFromString() {
        val testSubject = FragmentVariables.fromMap(mapOf("arg" to "TEST_VALUE"))
        val arg = testSubject.getEnum<TestEnum>("arg")
        assertEquals("TEST_VALUE", arg.value.toString())
    }

    @Test
    fun testEnumThrowsFromDelegate() {
        val testSubject = FragmentVariables.fromMap(mapOf("arg" to "TEST_VALUE"))

        val exception = assertThrows(IllegalArgumentException::class.java) {
            val arg: FragmentVariable<TestEnum?> by testSubject
            println(arg)
        }

        assertTrue(
            exception.message?.contains(
                "Don't use this method for enums: " +
                    "class viaduct.engine.api.fragment.FragmentVariablesTest\$TestEnum"
            ) == true
        )
    }

    @Test
    fun testValidateEnumInvalidString() {
        val testSubject = FragmentVariables.fromMap(mapOf("arg" to "wrong value"))

        val exception = assertThrows(IllegalArgumentException::class.java) {
            testSubject.getEnum<TestEnum>("arg")
        }

        assertTrue(
            exception.message?.contains(
                "No enum constant viaduct.engine.api.fragment.FragmentVariablesTest.TestEnum.wrong value"
            ) == true
        )
    }

    @Test
    fun testDefaultValues() {
        val testSubject = FragmentVariables.EMPTY
        val arg: FragmentVariable<String?> = testSubject["arg", "default value"]

        assertEquals("default value", arg.value.toString())
    }

    @Test
    fun testDefaultValuesUsingGetOperator() {
        val testSubject = FragmentVariables.EMPTY
        val arg: FragmentVariable<String?> = testSubject["arg", "default value"]

        assertEquals("default value", arg.value.toString())
    }

    @Test
    fun testInnerMapAllocation() {
        val fragmentVariables = FragmentVariables.fromMap(mapOf("testField" to "testValue"))
        assertEquals("testValue", fragmentVariables.innerMap["testField"]?.value)
    }
}
