package viaduct.utils.string

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StringUtilsTest {
    @Test
    fun testSha256Hash() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "".sha256Hash())
        assertEquals("d5579c46dfcc7f18207013e65b44e4cb4e2c2298f4ac457ba8f82743f31e930b", "test string".sha256Hash())
    }
}
