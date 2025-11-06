package viaduct.api.types

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

class ArgumentsTest {
    private val schema = SchemaUtils.getSchema()

    @Test
    fun testInputType() {
        val input = Arguments.inputType(
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
        assertTrue(idArg.appliedDirectives.any({ it.name == "idOf" }))
    }

    @Test
    fun testInvalidArgumentsClassName() {
        // Incorrect format - no underscore
        assertThrows<IllegalArgumentException> {
            Arguments.inputType("ArgumentedField", schema)
        }
        // Type doesn't exist
        assertThrows<IllegalArgumentException> {
            Arguments.inputType("SomeType_SomeField_Arguments", schema)
        }
    }

    @Test
    fun testInvalidArgumentsClassNameEmptyString() {
        // Edge case: empty string
        val exception = assertThrows<IllegalArgumentException> {
            Arguments.inputType("", schema)
        }
        assertTrue(exception.message?.contains("Invalid Arguments class name") ?: false)
    }

    @Test
    fun testNonexistentField() {
        // Type exists but field doesn't
        val exception = assertThrows<IllegalArgumentException> {
            Arguments.inputType("O1_nonexistentField_Arguments", schema)
        }
        assertTrue(exception.message?.contains("not found") ?: false)
    }

    @Test
    fun testFieldWithoutArguments() {
        // No arguments
        assertThrows<IllegalArgumentException> {
            Arguments.inputType("O1_stringField", schema)
        }
    }
}
