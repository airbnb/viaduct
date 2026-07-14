package viaduct.tenant.runtime.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.errors.TenantUsageException
import viaduct.service.api.spi.DecodedGlobalID
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.tenant.runtime.support.InputValueNormalizerCore.InputValueAdapter

class InputValueNormalizerCoreTest {
    /**
     * Records every serialize() call and returns a "$typeName:$internalID" string so tests can
     * assert both that the GlobalID branch fired and what arguments reached the codec, without
     * depending on a real codec implementation.
     */
    private class RecordingGlobalIDCodec : GlobalIDCodec {
        val serializeCalls = mutableListOf<Pair<String, String>>()

        override fun serialize(
            typeName: String,
            localID: String
        ): String {
            serializeCalls.add(typeName to localID)
            return "$typeName:$localID"
        }

        override fun deserialize(globalID: String): DecodedGlobalID = throw UnsupportedOperationException("not used in these tests")
    }

    /**
     * Marker value the fake adapter recognizes as a typed GlobalID. The core does not inspect this
     * type itself — it asks the adapter via globalIdPartsOrNull — so a plain holder is enough.
     */
    private data class FakeGlobalId(val typeName: String, val internalID: String)

    /** Marker value the fake adapter recognizes as a generated input wrapper. */
    private data class FakeInput(val inputData: Map<String, Any?>)

    /** Marker value the fake adapter rejects as an unsupported GRT. */
    private class FakeUnsupportedGrt(val message: String)

    /**
     * Fake adapter that recognizes [FakeGlobalId], [FakeInput], and [FakeUnsupportedGrt]. The
     * [recurseIntoInputData] mode is supplied per-instance so a single fake exercises both the
     * Kotlin (false) and Java (true) input-wrapper behaviors.
     */
    private class FakeAdapter(override val recurseIntoInputData: Boolean) : InputValueAdapter {
        override fun globalIdPartsOrNull(value: Any?): Pair<String, String>? = (value as? FakeGlobalId)?.let { it.typeName to it.internalID }

        override fun inputDataOrNull(value: Any?): Map<String, Any?>? = (value as? FakeInput)?.inputData

        override fun unsupportedGrtMessageOrNull(value: Any?): String? = (value as? FakeUnsupportedGrt)?.message
    }

    private enum class Color { RED, GREEN }

    private val recurseFalseAdapter = FakeAdapter(recurseIntoInputData = false)
    private val recurseTrueAdapter = FakeAdapter(recurseIntoInputData = true)

    @Test
    fun `null value passes through as null`() {
        val codec = RecordingGlobalIDCodec()

        val result = InputValueNormalizerCore.normalizeValueForEngine(null, codec, recurseFalseAdapter)

        assertNull(result)
        assertTrue(codec.serializeCalls.isEmpty(), "codec should not be touched for null")
    }

    @Test
    fun `GlobalID value is serialized via the codec`() {
        val codec = RecordingGlobalIDCodec()

        val result = InputValueNormalizerCore.normalizeValueForEngine(
            FakeGlobalId("User", "42"),
            codec,
            recurseFalseAdapter,
        )

        assertEquals("User:42", result)
        assertEquals(listOf("User" to "42"), codec.serializeCalls)
    }

    @Test
    fun `input wrapper with recurseIntoInputData false returns inputData unchanged without recursing`() {
        val codec = RecordingGlobalIDCodec()
        // A nested GlobalID inside the inputData would be serialized IF the core recursed; with
        // recurse=false it must be returned untouched, proving no recursion happened.
        val inputData = mapOf<String, Any?>("ref" to FakeGlobalId("User", "7"), "count" to 3)

        val result = InputValueNormalizerCore.normalizeValueForEngine(
            FakeInput(inputData),
            codec,
            recurseFalseAdapter,
        )

        assertSame(inputData, result, "the backing inputData map should be returned as-is")
        assertTrue(codec.serializeCalls.isEmpty(), "no recursion means the nested GlobalID is never serialized")
    }

    @Test
    fun `input wrapper with recurseIntoInputData true serializes nested GlobalID inside inputData`() {
        val codec = RecordingGlobalIDCodec()
        val inputData = mapOf<String, Any?>("ref" to FakeGlobalId("User", "7"), "count" to 3)

        val result = InputValueNormalizerCore.normalizeValueForEngine(
            FakeInput(inputData),
            codec,
            recurseTrueAdapter,
        )

        assertEquals(mapOf<String, Any?>("ref" to "User:7", "count" to 3), result)
        assertEquals(listOf("User" to "7"), codec.serializeCalls)
    }

    @Test
    fun `enum value is normalized to its name`() {
        val codec = RecordingGlobalIDCodec()

        val result = InputValueNormalizerCore.normalizeValueForEngine(Color.GREEN, codec, recurseFalseAdapter)

        assertEquals("GREEN", result)
    }

    @Test
    fun `nested map values are recursively normalized`() {
        val codec = RecordingGlobalIDCodec()
        val value = mapOf<String, Any?>(
            "id" to FakeGlobalId("Listing", "99"),
            "color" to Color.RED,
            "plain" to "keep",
        )

        val result = InputValueNormalizerCore.normalizeValueForEngine(value, codec, recurseFalseAdapter)

        assertEquals(
            mapOf<String, Any?>("id" to "Listing:99", "color" to "RED", "plain" to "keep"),
            result,
        )
    }

    @Test
    fun `iterable elements are recursively normalized into a list`() {
        val codec = RecordingGlobalIDCodec()
        val value = listOf(FakeGlobalId("User", "1"), Color.GREEN, "plain")

        val result = InputValueNormalizerCore.normalizeValueForEngine(value, codec, recurseFalseAdapter)

        assertEquals(listOf<Any?>("User:1", "GREEN", "plain"), result)
    }

    @Test
    fun `array elements are recursively normalized and returned as a list`() {
        val codec = RecordingGlobalIDCodec()
        val value = arrayOf<Any?>(FakeGlobalId("User", "2"), "plain")

        val result = InputValueNormalizerCore.normalizeValueForEngine(value, codec, recurseFalseAdapter)

        assertEquals(listOf<Any?>("User:2", "plain"), result)
    }

    @Test
    fun `unsupported GRT throws TenantUsageException carrying the adapter message`() {
        val codec = RecordingGlobalIDCodec()

        val exception = assertThrows<TenantUsageException> {
            InputValueNormalizerCore.normalizeValueForEngine(
                FakeUnsupportedGrt("Type Foo is not a supported input"),
                codec,
                recurseFalseAdapter,
            )
        }

        assertEquals("Type Foo is not a supported input", exception.message)
    }

    @Test
    fun `plain scalar passes through unchanged`() {
        val codec = RecordingGlobalIDCodec()

        val result = InputValueNormalizerCore.normalizeValueForEngine("just a string", codec, recurseFalseAdapter)

        assertEquals("just a string", result)
        assertTrue(codec.serializeCalls.isEmpty())
    }

    @Test
    fun `normalizeVariablesForEngine normalizes every entry`() {
        val codec = RecordingGlobalIDCodec()
        val variables = mapOf<String, Any?>(
            "ref" to FakeGlobalId("User", "5"),
            "status" to Color.RED,
            "name" to "Alice",
        )

        val result = InputValueNormalizerCore.normalizeVariablesForEngine(variables, codec, recurseFalseAdapter)

        assertEquals(
            mapOf<String, Any?>("ref" to "User:5", "status" to "RED", "name" to "Alice"),
            result,
        )
    }
}
