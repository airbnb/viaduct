@file:Suppress("ForbiddenImport")

package viaduct.java.runtime.bridge

import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.errors.TenantUsageException
import viaduct.java.api.internal.InputBase
import viaduct.java.api.reflect.Type
import viaduct.java.api.types.GraphQLEnum
import viaduct.java.api.types.GraphQLObject
import viaduct.java.api.types.NodeObject
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.testing.NormalizedValue
import viaduct.tenant.runtime.testing.normalizedVariables

class JavaTenantApiValueNormalizerTest {
    private val engineContext = mockk<EngineExecutionContext> {
        every { globalIDCodec } returns GlobalIDCodecDefault
    }

    @Test
    fun `normalizes nested Java Tenant API values for engine variables`() {
        val globalID = GlobalIDImpl(type = nodeType("User"), internalId = "1234")
        val serializedGlobalID = GlobalIDCodecDefault.serialize("User", "1234")
        val nestedInput = TestJavaInput(
            mapOf(
                "ids" to listOf(globalID),
                "status" to TestStatus.ACTIVE,
            ),
        )
        val input = TestJavaInput(
            mapOf(
                "id" to globalID,
                "nested" to nestedInput,
                "rawMap" to mapOf("id" to globalID),
                "array" to arrayOf(globalID),
            ),
        )

        assertEquals(
            mapOf(
                "input" to mapOf(
                    "id" to serializedGlobalID,
                    "nested" to mapOf(
                        "ids" to listOf(serializedGlobalID),
                        "status" to "ACTIVE",
                    ),
                    "rawMap" to mapOf("id" to serializedGlobalID),
                    "array" to listOf(serializedGlobalID),
                ),
                "nullable" to null,
                "scalar" to 42,
            ),
            JavaTenantApiInputValueNormalizer.normalizeVariablesForEngine(
                mapOf(
                    "input" to input,
                    "nullable" to null,
                    "scalar" to 42,
                ),
                engineContext,
            ),
        )
    }

    @Test
    fun `normalizes arbitrary nested Java Tenant API values for engine variables`(): Unit =
        runBlocking {
            Arb.normalizedVariables(javaTenantApiLeaf()).checkAll { case ->
                assertEquals(case.normalized, JavaTenantApiInputValueNormalizer.normalizeVariablesForEngine(case.raw, engineContext))
            }
        }

    @Test
    fun `rejects unsupported Java Tenant API GRT values`() {
        val ex = assertThrows<TenantUsageException> {
            JavaTenantApiInputValueNormalizer.normalizeVariablesForEngine(
                mapOf("object" to object : GraphQLObject {}),
                engineContext,
            )
        }
        ex.shouldBeInstanceOf<TenantUsageException>()
        assertTrue(ex.message!!.contains("Unsupported Java Tenant API value in engine variables"))
    }

    private fun javaTenantApiLeaf(): Arb<NormalizedValue> =
        arbitrary {
            val kind = Arb.int(0..5).bind()
            val text = Arb.string(8, Codepoint.alphanumeric()).bind()
            val number = Arb.int().bind()
            val bool = Arb.boolean().bind()

            when (kind) {
                0 -> NormalizedValue(null, null)
                1 -> NormalizedValue(text, text)
                2 -> NormalizedValue(number, number)
                3 -> NormalizedValue(bool, bool)
                4 -> NormalizedValue(TestStatus.ACTIVE, "ACTIVE")
                else -> NormalizedValue(
                    GlobalIDImpl(type = nodeType("User"), internalId = text),
                    GlobalIDCodecDefault.serialize("User", text),
                )
            }
        }

    private enum class TestStatus : GraphQLEnum {
        ACTIVE,
    }

    @Suppress("UNCHECKED_CAST")
    private class TestJavaInput(
        inputData: Map<String, Any?>,
    ) : InputBase(null, inputData as Map<String, Any>, null)

    private fun nodeType(name: String): Type<NodeObject> =
        object : Type<NodeObject> {
            override fun getName(): String = name

            override fun getJavaClass(): Class<out NodeObject> = NodeObject::class.java
        }
}
