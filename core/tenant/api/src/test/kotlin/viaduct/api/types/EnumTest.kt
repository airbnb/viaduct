package viaduct.api.types

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EnumTest {
    @Test
    fun testEnumFrom() {
        val a = Enum.enumFrom(TestEnum::class, "A")
        assert(a == TestEnum.A)
        val b = Enum.enumFrom(TestEnum::class, "B")
        assert(b == TestEnum.B)
        val c = Enum.enumFrom(TestEnum::class, "C")
        assert(c == TestEnum.C)
        assertThrows<NoSuchElementException> {
            Enum.enumFrom(TestEnum::class, "D")
        }
    }
}

enum class TestEnum : Enum {
    A,
    B,
    C
}
