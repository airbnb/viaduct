@file:Suppress("DEPRECATION")

package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.runStandardViaductFeatureTest
import viaduct.engine.runtime.tenantloading.MissingResolversException

/** Uses the unadapted service boundary so fixture defaults cannot hide missing executors. */
interface ResolverValidationContract {
    val engine2Enabled: Boolean

    @Test
    fun `strict validation rejects missing annotated nested field resolvers`() {
        val module = module(emptyMap())
        val failure = assertThrows<MissingResolversException> {
            module.runStandardViaductFeatureTest(engine2Enabled, lenientResolverValidation = false) {}
        }
        assertEquals(listOf("Item.value"), failure.missingFieldResolvers)
        assertEquals(emptyList<String>(), failure.missingNodeResolvers)
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "null", "value"])
    fun `lenient validation permits backing data but still reports absent fields`(backingData: String) {
        val expected = if (backingData == "value") "backing-data" else null
        val fields = if (backingData == "absent") emptyMap() else mapOf("value" to expected)
        module(fields).runStandardViaductFeatureTest(engine2Enabled) {
            val result = runQuery("{ item { alias: value } }")
            if (backingData == "absent") {
                assertEquals(1, result.errors.size)
                assertEquals(listOf("item", "alias"), result.errors.single().path)
            } else {
                assertEquals(emptyList<Any>(), result.errors)
            }
            assertEquals(mapOf("item" to mapOf("alias" to expected)), result.getData())
        }
    }

    private fun module(fields: Map<String, Any?>): EngineTestModule =
        EngineTestModule("extend type Query { item: Item @resolver } type Item { value: String @resolver }") {
            fieldWithValue("Query" to "item", ResolvedEngineObjectData(schema.schema.getObjectType("Item"), fields))
        }
}
