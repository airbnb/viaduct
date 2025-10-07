package viaduct.arbitrary.graphql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.mapping.graphql.RawScalar
import viaduct.mapping.graphql.RawValue

class ScalarValueMappersTest : RawValue.DSL() {
    private val roundtrip = ScalarRawToGJ.map(ScalarGJToRaw)

    private fun assertRoundtrip(
        typename: String,
        value: RawScalar
    ) = assertEquals(value, roundtrip(typename, value))

    private fun assertIllegal(
        typename: String,
        value: RawScalar
    ) = assertThrows<IllegalArgumentException> {
        roundtrip(typename, value)
    }

    @Test
    fun `roundtrip`() {
        assertRoundtrip("String", "a".scalar)
        assertRoundtrip("ID", "a".scalar)
        assertRoundtrip("Int", 1.scalar)
        assertRoundtrip("Float", 1.0.scalar)
        assertRoundtrip("Boolean", true.scalar)
    }

    @Test
    fun `throws on unsupported scalars`() {
        assertIllegal("Unknown", "a".scalar)
        assertIllegal("Unsupported", "a".scalar)
    }
}
