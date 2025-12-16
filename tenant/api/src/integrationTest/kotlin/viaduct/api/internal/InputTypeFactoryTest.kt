package viaduct.api.internal

import graphql.language.IntValue
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLTypeUtil
import java.math.BigInteger
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

    // ========== Tests for argumentsInputType() ==========

    @Test
    fun `argumentsInputType with valid name succeeds`() {
        val input = InputTypeFactory.argumentsInputType(
            "O2_ArgumentedField_Arguments",
            schema
        )
        assertEquals("O2_ArgumentedField_Arguments", input.name)
        assertEquals(4, input.fields.size)

        val stringArgType = input.getField("stringArg").type
        assertTrue(stringArgType is GraphQLNonNull)
        assertEquals("String", (GraphQLTypeUtil.unwrapNonNull(stringArgType) as GraphQLNamedSchemaElement).name)

        val intArg = input.getField("intArgWithDefault")
        assertEquals(BigInteger("1"), (intArg.inputFieldDefaultValue.value as IntValue).value)

        val inputArgType = input.getField("inputArg").type
        assertEquals("Input1", (inputArgType as GraphQLInputObjectType).name)

        val idArg = input.getField("idArg")
        assertTrue(idArg.appliedDirectives.any { it.name == "idOf" })
    }

    @Test
    fun `argumentsInputType with invalid format - no underscores throws IAE`() {
        val exception = assertThrows<IllegalArgumentException> {
            InputTypeFactory.argumentsInputType("ArgumentedField", schema)
        }
        assertTrue(exception.message?.contains("Invalid Arguments class name") ?: false)
        assertTrue(exception.message?.contains("Expected format: TypeName_FieldName_Arguments") ?: false)
    }

    @Test
    fun `argumentsInputType with invalid format - wrong suffix throws IAE`() {
        assertThrows<IllegalArgumentException> {
            InputTypeFactory.argumentsInputType("O2_ArgumentedField_Wrong", schema)
        }
    }

    @Test
    fun `argumentsInputType with empty type name throws IAE`() {
        val exception = assertThrows<IllegalArgumentException> {
            InputTypeFactory.argumentsInputType("_ArgumentedField_Arguments", schema)
        }
        assertTrue(exception.message?.contains("Invalid Arguments class name") ?: false)
    }

    @Test
    fun `argumentsInputType with empty field name throws IAE`() {
        val exception = assertThrows<IllegalArgumentException> {
            InputTypeFactory.argumentsInputType("O2__Arguments", schema)
        }
        assertTrue(exception.message?.contains("Invalid Arguments class name") ?: false)
    }

    @Test
    fun `argumentsInputType with empty string throws IAE`() {
        val exception = assertThrows<IllegalArgumentException> {
            InputTypeFactory.argumentsInputType("", schema)
        }
        assertTrue(exception.message?.contains("Invalid Arguments class name") ?: false)
    }

    @Test
    fun `argumentsInputType with type not in schema throws IAE`() {
        val exception = assertThrows<IllegalArgumentException> {
            InputTypeFactory.argumentsInputType("NonExistent_someField_Arguments", schema)
        }
        assertTrue(exception.message?.contains("not in schema") ?: false)
    }

    @Test
    fun `argumentsInputType with nonexistent field throws IAE`() {
        val exception = assertThrows<IllegalArgumentException> {
            InputTypeFactory.argumentsInputType("O1_nonexistentField_Arguments", schema)
        }
        assertTrue(exception.message?.contains("not found") ?: false)
    }

    @Test
    fun `argumentsInputType with field without arguments throws IAE`() {
        // O1_stringField is a field that exists but has no arguments
        assertThrows<IllegalArgumentException> {
            InputTypeFactory.argumentsInputType("O1_StringField_Arguments", schema)
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

    // ========== Tests for JvmStatic annotation ==========

    @Test
    fun `InputTypeFactory methods are static for Java interop`() {
        // Verify that @JvmStatic makes methods accessible as static from Java
        val clazz = InputTypeFactory::class.java

        // Check argumentsInputType is a static method
        val argumentsMethod = clazz.getDeclaredMethod(
            "argumentsInputType",
            String::class.java,
            viaduct.engine.api.ViaductSchema::class.java
        )
        assertTrue(java.lang.reflect.Modifier.isStatic(argumentsMethod.modifiers))

        // Check inputObjectInputType is a static method
        val inputMethod = clazz.getDeclaredMethod(
            "inputObjectInputType",
            String::class.java,
            viaduct.engine.api.ViaductSchema::class.java
        )
        assertTrue(java.lang.reflect.Modifier.isStatic(inputMethod.modifiers))
    }

    // ========== Tests for error message consistency ==========

    @Test
    fun `error messages match original API behavior`() {
        // Test that error messages are consistent with the original
        // Arguments.inputType() and Input.inputType() implementations

        // Arguments error message format
        val argException = assertThrows<IllegalArgumentException> {
            InputTypeFactory.argumentsInputType("InvalidFormat", schema)
        }
        assertTrue(argException.message?.contains("Invalid Arguments class name") ?: false)

        // Input error message format - should match original "does not exist in schema"
        val inputException = assertThrows<IllegalArgumentException> {
            InputTypeFactory.inputObjectInputType("NonExistent", schema)
        }
        assertTrue(inputException.message?.contains("does not exist in schema") ?: false)
    }
}
