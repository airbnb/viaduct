package viaduct.api.internal

import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLTypeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.schemautils.SchemaUtils

/**
 * Unit tests for InputTypeFactory - the internal factory for creating
 * GraphQLInputObjectType instances for Arguments and Input GRTs.
 */
class InputTypeFactoryTest {
    private val schema = SchemaUtils.getSchema()

    // ========== Tests for argumentsInputType ==========

    @Test
    fun `argumentsInputType with explicit params succeeds for simple names`() {
        val input = InputTypeFactory.argumentsInputType("O2_ArgumentedField_Arguments", "O2", "argumentedField", schema)
        assertEquals("O2_ArgumentedField_Arguments", input.name)
        assertEquals(4, input.fields.size)
    }

    @Test
    fun `argumentsInputType with explicit params succeeds for underscore type names`() {
        val input = InputTypeFactory.argumentsInputType("Under_Score_SomeField_Arguments", "Under_Score", "someField", schema)
        assertEquals("Under_Score_SomeField_Arguments", input.name)
        assertEquals(2, input.fields.size)

        val stringArgType = input.getField("stringArg").type
        assertTrue(stringArgType is GraphQLNonNull)
        assertEquals("String", (GraphQLTypeUtil.unwrapNonNull(stringArgType) as GraphQLNamedSchemaElement).name)
    }

    @Test
    fun `argumentsInputType with explicit params - type not in schema throws IAE`() {
        val exception = assertThrows<IllegalArgumentException> {
            InputTypeFactory.argumentsInputType("NonExistent_SomeField_Arguments", "NonExistent", "someField", schema)
        }
        assertTrue(exception.message?.contains("not in schema") ?: false)
    }

    @Test
    fun `argumentsInputType with explicit params - field not found throws IAE`() {
        val exception = assertThrows<IllegalArgumentException> {
            InputTypeFactory.argumentsInputType("O1_NonexistentField_Arguments", "O1", "nonexistentField", schema)
        }
        assertTrue(exception.message?.contains("not found") ?: false)
    }

    @Test
    fun `argumentsInputType with explicit params - field without arguments throws IAE`() {
        assertThrows<IllegalArgumentException> {
            InputTypeFactory.argumentsInputType("O1_StringField_Arguments", "O1", "stringField", schema)
        }
    }

    // ========== Tests for inputObjectInputType() ==========

    @Test
    fun `inputObjectInputType with valid name succeeds`() {
        val inputType = InputTypeFactory.inputObjectInputType("Input1", schema)
        assertEquals("Input1", inputType.name)
        assertTrue(inputType.fields.isNotEmpty())
    }

    @Test
    fun `inputObjectInputType with type not in schema throws IAE`() {
        val exception = assertThrows<IllegalArgumentException> {
            InputTypeFactory.inputObjectInputType("NonExistentInput", schema)
        }
        assertTrue(exception.message?.contains("does not exist in schema") ?: false)
    }

    @Test
    fun `inputObjectInputType with non-input type throws IAE`() {
        // O1 is an object type, not an input type
        val exception = assertThrows<IllegalArgumentException> {
            InputTypeFactory.inputObjectInputType("O1", schema)
        }
        assertTrue(exception.message?.contains("is not an input type") ?: false)
    }

    @Test
    fun `inputObjectInputType with empty string throws IAE`() {
        assertThrows<IllegalArgumentException> {
            InputTypeFactory.inputObjectInputType("", schema)
        }
    }
}
