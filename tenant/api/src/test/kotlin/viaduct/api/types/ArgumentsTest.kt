package viaduct.api.types

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.internal.InputTypeFactory
import viaduct.api.schemautils.SchemaUtils

class ArgumentsTest {
    private val schema = SchemaUtils.getSchema()

    @Test
    fun testInputType() {
        val input = InputTypeFactory.argumentsInputType("O2_ArgumentedField_Arguments", "O2", "argumentedField", schema)
        assertEquals("O2_ArgumentedField_Arguments", input.name)
        assertEquals(4, input.fields.size)
    }

    @Test
    fun testInputTypeWithUnderscoreTypeName() {
        val input = InputTypeFactory.argumentsInputType("Under_Score_SomeField_Arguments", "Under_Score", "someField", schema)
        assertEquals("Under_Score_SomeField_Arguments", input.name)
        assertEquals(2, input.fields.size)
    }
}
