package viaduct.api.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.MockSchema
import viaduct.errors.UnsetFieldException

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayEngineObjectDataTest {
    private val schema = MockSchema.mk(
        """
        extend type Query {
            test: TestType
        }
        type TestType {
            field1: String
            field2: Int
            field3: String
        }
        """.trimIndent()
    )

    private val testType = schema.schema.getObjectType("TestType")

    companion object {
        private const val OVERLAY_VALUE = "overlay_value"
        private const val BASE_VALUE = "base_value"
        private const val FIELD_1 = "field1"
        private const val FIELD_2 = "field2"
        private const val FIELD_3 = "field3"
    }

    @Test
    fun `fetch - returns overlay value when present`() =
        runTest {
            val base = ResolvedEngineObjectData.Builder(testType)
                .put(FIELD_1, BASE_VALUE)
                .build()

            val overlay = ResolvedEngineObjectData.Builder(testType)
                .put(FIELD_1, OVERLAY_VALUE)
                .build()

            val combined = OverlayEngineObjectData(overlay, base)

            Assertions.assertEquals(OVERLAY_VALUE, combined.fetch(FIELD_1))
        }

    @Test
    fun `fetch - falls back to base when not in overlay`() =
        runTest {
            val base = ResolvedEngineObjectData.Builder(testType)
                .put(FIELD_1, BASE_VALUE)
                .build()

            val overlay = ResolvedEngineObjectData.Builder(testType)
                .build()

            val combined = OverlayEngineObjectData(overlay, base)

            Assertions.assertEquals(BASE_VALUE, combined.fetch(FIELD_1))
        }

    @Test
    fun `fetch - throws when field not in overlay or base`() =
        runTest {
            val base = ResolvedEngineObjectData.Builder(testType)
                .build()

            val overlay = ResolvedEngineObjectData.Builder(testType)
                .build()

            val combined = OverlayEngineObjectData(overlay, base)
            org.junit.jupiter.api.assertThrows<UnsetFieldException> {
                combined.fetch(FIELD_1)
            }
        }

    @Test
    fun `fetch - overlay null overrides base non-null value`() =
        runTest {
            val base = ResolvedEngineObjectData.Builder(testType)
                .put(FIELD_1, BASE_VALUE)
                .build()

            val overlay = ResolvedEngineObjectData.Builder(testType)
                .put(FIELD_1, null)
                .build()

            val combined = OverlayEngineObjectData(overlay, base)

            Assertions.assertEquals(null, combined.fetch(FIELD_1))
        }

    @Test
    fun `fetchOrNull - returns overlay value when present`() =
        runTest {
            val base = ResolvedEngineObjectData.Builder(testType)
                .put(FIELD_1, BASE_VALUE)
                .build()

            val overlay = ResolvedEngineObjectData.Builder(testType)
                .put(FIELD_1, OVERLAY_VALUE)
                .build()

            val combined = OverlayEngineObjectData(overlay, base)

            Assertions.assertEquals(OVERLAY_VALUE, combined.fetchOrNull(FIELD_1))
        }

    @Test
    fun `fetchOrNull - falls back to base when not in overlay`() =
        runTest {
            val base = ResolvedEngineObjectData.Builder(testType)
                .put(FIELD_1, BASE_VALUE)
                .build()

            val overlay = ResolvedEngineObjectData.Builder(testType)
                .build()

            val combined = OverlayEngineObjectData(overlay, base)

            Assertions.assertEquals(BASE_VALUE, combined.fetchOrNull(FIELD_1))
        }

    @Test
    fun `fetchOrNull - returns null when not in overlay or base`() =
        runTest {
            val base = ResolvedEngineObjectData.Builder(testType)
                .build()

            val overlay = ResolvedEngineObjectData.Builder(testType)
                .build()

            val combined = OverlayEngineObjectData(overlay, base)

            Assertions.assertEquals(null, combined.fetchOrNull(FIELD_1))
        }

    @Test
    fun `isPresent combines overlay and base presence including explicit null`() {
        val base = ResolvedEngineObjectData.Builder(testType)
            .put(FIELD_1, BASE_VALUE)
            .build()
        val overlay = ResolvedEngineObjectData.Builder(testType)
            .put(FIELD_2, null)
            .build()

        val combined = OverlayEngineObjectData(overlay, base)

        Assertions.assertTrue(combined.isPresent(FIELD_1))
        Assertions.assertTrue(combined.isPresent(FIELD_2))
        Assertions.assertFalse(combined.isPresent(FIELD_3))
    }

    @Test
    fun `present selection omitted from overlay enumeration masks base value`() {
        val base = ResolvedEngineObjectData.Builder(testType)
            .put(FIELD_1, BASE_VALUE)
            .build()
        val emptyOverlay = ResolvedEngineObjectData.Builder(testType).build()
        val overlay = object : EngineObjectData.Sync by emptyOverlay {
            override fun get(selection: String): Any? = null

            override fun getOrNull(selection: String): Any? = null

            override fun isPresent(selection: String): Boolean = selection == FIELD_1
        }

        val combined = OverlayEngineObjectData(overlay, base)

        Assertions.assertNull(combined.get(FIELD_1))
        Assertions.assertNull(combined.getOrNull(FIELD_1))
    }

    @Test
    fun `fetchSelections returns union of overlay and base selections`() =
        runTest {
            val base = ResolvedEngineObjectData.Builder(testType)
                .put(FIELD_1, BASE_VALUE)
                .put(FIELD_2, 42)
                .build()

            val overlay = ResolvedEngineObjectData.Builder(testType)
                .put(FIELD_2, 99)
                .put(FIELD_3, OVERLAY_VALUE)
                .build()

            val combined = OverlayEngineObjectData(overlay, base)

            val selections = combined.fetchSelections().toSet()
            Assertions.assertEquals(setOf(FIELD_1, FIELD_2, FIELD_3), selections)
        }

    @Test
    fun `multiple field overrides work correctly`() =
        runTest {
            val base = ResolvedEngineObjectData.Builder(testType)
                .put(FIELD_1, "base1")
                .put(FIELD_2, 10)
                .put(FIELD_3, "base3")
                .build()

            val overlay = ResolvedEngineObjectData.Builder(testType)
                .put(FIELD_1, "overlay1")
                .put(FIELD_3, "overlay3")
                .build()

            val combined = OverlayEngineObjectData(overlay, base)

            Assertions.assertEquals("overlay1", combined.fetch(FIELD_1))
            Assertions.assertEquals(10, combined.fetch(FIELD_2)) // Falls back to base
            Assertions.assertEquals("overlay3", combined.fetch(FIELD_3))
        }
}
