@file:Suppress("ForbiddenImport")

package viaduct.api.internal

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.mocks.MockInternalContext
import viaduct.api.schemautils.SchemaUtils
import viaduct.api.testschema.E1
import viaduct.api.testschema.Input2
import viaduct.api.testschema.TestType
import viaduct.engine.api.ResolvedEngineObjectData

class WrapUtilsTest {
    private val schema = SchemaUtils.getSchema()
    private val internalContext = MockInternalContext.create(schema, "viaduct.api.testschema")

    @Test
    fun `wrapEnum -- simple`() {
        assertEquals(
            E1.A,
            wrapEnum(internalContext, schema.schema.getTypeAs("E1"), "A")
        )
    }

    @Test
    fun `wrapEnum -- throws on unknown value`() {
        assertThrows<IllegalArgumentException> {
            wrapEnum(
                internalContext,
                schema.schema.getTypeAs("E1"),
                "Unknown"
            )
        }
    }

    @Test
    fun `wrapEnum -- returns null for UNDEFINED`() {
        assertNull(
            wrapEnum(
                internalContext,
                schema.schema.getTypeAs("E1"),
                "UNDEFINED"
            )
        )
    }

    @Test
    fun `wrapInputObject -- simple`() {
        val inp = wrapInputObject(
            internalContext,
            Input2.Reflection,
            schema.schema.getTypeAs("Input2"),
            mapOf("stringField" to "foo")
        )
        assertEquals("foo", inp.stringField)
    }

    @Test
    fun `wrapOutputObject -- simple`(): Unit =
        runBlocking {
            val obj = wrapOutputObject(
                internalContext,
                TestType.Reflection,
                ResolvedEngineObjectData(
                    schema.schema.getTypeAs("TestType"),
                    mapOf("id" to "foo")
                )
            )
            assertEquals("foo", obj.getId())
        }
}
