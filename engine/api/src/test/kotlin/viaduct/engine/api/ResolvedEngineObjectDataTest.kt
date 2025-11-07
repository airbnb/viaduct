package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.mkSchema

class ResolvedEngineObjectDataTest {
    private val schema = mkSchema(
        """
            type Obj { x:Int }
            extend type Query { x:Int }
        """.trimIndent()
    )
    private val obj = schema.schema.getObjectType("Obj")

    @Test
    fun properties() {
        val eod = ResolvedEngineObjectData.Builder(obj)
            .put("x", 1)
            .put("y", null)
            .build()

        assertSame(obj, eod.graphQLObjectType)
        assertEquals(1, eod.get("x"))
        assertEquals(null, eod.get("y"))
        assertThrows<UnsetSelectionException> {
            eod.get("unset")
        }
        assertEquals(null, eod.getOrNull("unset"))
        assertEquals(listOf("x", "y"), eod.getSelections().toList())
    }

    @Test
    fun `Builder -- objects constructed by Builder are immutable`() {
        // Ensure that if a Builder is reused, that it does not modify already created objects

        val builder = ResolvedEngineObjectData.Builder(obj).put("x", 1)
        val eod1 = builder.build()
        val eod2 = builder.put("x", 2).build()

        assertEquals(1, eod1.get("x"))
        assertEquals(2, eod2.get("x"))
    }
}
