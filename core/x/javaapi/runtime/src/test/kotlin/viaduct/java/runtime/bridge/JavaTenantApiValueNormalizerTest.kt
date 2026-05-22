package viaduct.java.runtime.bridge

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineExecutionContext
import viaduct.errors.TenantUsageException
import viaduct.java.api.internal.InputBase
import viaduct.java.api.reflect.Type
import viaduct.java.api.types.GraphQLEnum
import viaduct.java.api.types.GraphQLObject
import viaduct.java.api.types.NodeObject
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

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

        assertThat(
            JavaTenantApiInputValueNormalizer.normalizeVariablesForEngine(
                mapOf(
                    "input" to input,
                    "nullable" to null,
                    "scalar" to 42,
                ),
                engineContext,
            ),
        ).isEqualTo(
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
        )
    }

    @Test
    fun `rejects unsupported Java Tenant API GRT values`() {
        assertThatThrownBy {
            JavaTenantApiInputValueNormalizer.normalizeVariablesForEngine(
                mapOf("object" to object : GraphQLObject {}),
                engineContext,
            )
        }.isInstanceOf(TenantUsageException::class.java)
            .hasMessageContaining("Unsupported Java Tenant API value in engine variables")
    }

    private enum class TestStatus : GraphQLEnum {
        ACTIVE,
    }

    private class TestJavaInput(
        inputData: Map<String, Any>,
    ) : InputBase(inputData)

    private fun nodeType(name: String): Type<NodeObject> =
        object : Type<NodeObject> {
            override fun getName(): String = name

            override fun getJavaClass(): Class<out NodeObject> = NodeObject::class.java
        }
}
