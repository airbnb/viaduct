@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime

import graphql.schema.GraphQLInputObjectType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.InternalContext
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockReflectionLoader
import viaduct.api.mocks.MockType
import viaduct.api.mocks.testGlobalId
import viaduct.api.types.Object
import viaduct.engine.api.mocks.MockSchema
import viaduct.errors.TenantUsageException
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.testing.NormalizedValue
import viaduct.tenant.runtime.testing.normalizedVariables

class TenantApiValueNormalizerTest {
    private val globalIDCodec = GlobalIDCodecDefault
    private val reflectionLoader = MockReflectionLoader()
    private val context = MockInternalContext(MockSchema.minimal, globalIDCodec, reflectionLoader)
    private val inputType = GraphQLInputObjectType.newInputObject().name("TestInput").build()

    @Test
    fun `normalizes nested Tenant API values from raw maps for engine variables`() {
        val userType = MockType.mkNodeObject("User")
        val globalID = GlobalID(userType, "1234")
        val serializedGlobalID = userType.testGlobalId("1234")
        val nestedInputData = mapOf(
            "ids" to listOf(serializedGlobalID),
            "status" to "ACTIVE",
        )
        val inputData = mapOf(
            "id" to serializedGlobalID,
            "nested" to nestedInputData,
            "rawMap" to mapOf("id" to serializedGlobalID),
            "array" to listOf(serializedGlobalID),
        )
        val input = TestInput(
            context,
            inputType,
            inputData,
        )

        assertEquals(
            mapOf(
                "input" to inputData,
                "rawMap" to mapOf(
                    "id" to serializedGlobalID,
                    "status" to "ACTIVE",
                ),
                "array" to listOf(serializedGlobalID),
                "nullable" to null,
                "scalar" to 42,
            ),
            TenantApiInputValueNormalizer.normalizeVariablesForEngine(
                mapOf(
                    "input" to input,
                    "rawMap" to mapOf(
                        "id" to globalID,
                        "status" to TestStatus.ACTIVE,
                    ),
                    "array" to arrayOf(globalID),
                    "nullable" to null,
                    "scalar" to 42,
                ),
                globalIDCodec,
            ),
        )
    }

    @Test
    fun `does not recursively normalize InputLikeBase inputData`() {
        val userType = MockType.mkNodeObject("User")
        val globalID = GlobalID(userType, "1234")
        val inputData = mapOf(
            "id" to globalID,
            "status" to TestStatus.ACTIVE,
        )
        val input = TestInput(context, inputType, inputData)

        assertEquals(
            mapOf("input" to inputData),
            TenantApiInputValueNormalizer.normalizeVariablesForEngine(
                mapOf("input" to input),
                globalIDCodec,
            ),
        )
    }

    @Test
    fun `normalizes arbitrary nested Tenant API values from raw maps for engine variables`(): Unit =
        runBlocking {
            Arb.normalizedVariables(tenantApiLeaf()).checkAll { case ->
                assertEquals(
                    case.normalized,
                    TenantApiInputValueNormalizer.normalizeVariablesForEngine(case.raw, globalIDCodec),
                )
            }
        }

    @Test
    fun `rejects unsupported Tenant API GRT values`() {
        val exception = assertThrows<TenantUsageException> {
            TenantApiInputValueNormalizer.normalizeVariablesForEngine(
                mapOf("object" to object : Object {}),
                globalIDCodec,
            )
        }

        assertTrue(exception.message!!.contains("Unsupported Tenant API value in engine variables"))
    }

    private fun tenantApiLeaf(): Arb<NormalizedValue> =
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
                else -> {
                    val type = MockType.mkNodeObject("User")
                    NormalizedValue(GlobalID(type, text), type.testGlobalId(text))
                }
            }
        }

    private enum class TestStatus : viaduct.api.types.Enum {
        ACTIVE,
    }

    private class TestInput(
        override val context: InternalContext,
        override val graphQLInputObjectType: GraphQLInputObjectType,
        override val inputData: Map<String, Any?>,
    ) : InputLikeBase()
}
