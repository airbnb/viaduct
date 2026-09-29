package semantics.arbitrary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeneratorConfigDataTest {
    @Test
    fun `generator config data contains every resolved value and reconstructs config`() {
        val original =
            Config.default +
                (ArgumentsEnabled to false) +
                (SchemaObjectCount to 7..9) +
                (MaxSelectionDepth to 6) +
                (SelectiveNodeResolversEnabled to true) +
                (ResolverVariableWeight to 0.75)
        val data = GeneratorConfigData.from("round-trip", original)
        val restored = data.toConfig()

        assertEquals(false, restored[ArgumentsEnabled])
        assertEquals(7..9, restored[SchemaObjectCount])
        assertEquals(6, restored[MaxSelectionDepth])
        assertEquals(true, restored[SelectiveNodeResolversEnabled])
        assertEquals(0.75, restored[ResolverVariableWeight])
        assertEquals(ConfigKeys.all.size, data.keyNames().size)
    }

    @Test
    fun `generator config rejects missing unknown and mistyped keys`() {
        val valid = GeneratorConfigData.from("invalid", Config.default)

        assertFailsWith<IllegalArgumentException> {
            valid.copy(booleans = valid.booleans - ArgumentsEnabled.wireName).toConfig()
        }
        assertFailsWith<IllegalArgumentException> {
            valid.copy(integers = valid.integers + ("unknown" to 1)).toConfig()
        }
        assertFailsWith<IllegalArgumentException> {
            valid
                .copy(
                    booleans = valid.booleans - ArgumentsEnabled.wireName,
                    integers = valid.integers + (ArgumentsEnabled.wireName to 1),
                ).toConfig()
        }
    }
}

private fun GeneratorConfigData.keyNames(): Set<String> = booleans.keys + integers.keys + doubles.keys + ranges.keys
