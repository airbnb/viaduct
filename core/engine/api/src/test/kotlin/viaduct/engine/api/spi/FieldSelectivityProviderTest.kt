package viaduct.engine.api.spi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import viaduct.engine.api.Coordinate

class FieldSelectivityProviderTest {
    private val coordinate = Coordinate("TestType", "testField")

    @Test
    fun `Never returns false`() {
        assertThat(FieldSelectivityProvider.Never.isSelective(coordinate)).isFalse()
    }

    @Test
    fun `Always returns true`() {
        assertThat(FieldSelectivityProvider.Always.isSelective(coordinate)).isTrue()
    }
}
