package viaduct.tenant.runtime.jvm

import graphql.Scalars
import graphql.language.IntValue
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLNonNull
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InputDataValidatorTest {
    private val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
        SchemaParser().parse(
            """
            scalar JSON
            enum Color { RED }
            input Child { required: String!, defaulted: Int! = 5 }
            input Choice @oneOf { byName: String, byCount: Int }
            input Parent {
                child: Child
                children: [Child!]
                matrix: [[Int!]!]!
                loose: [[Int]]
                color: Color
                json: JSON
                choice: Choice
            }
            type Query { value: String }
            """.trimIndent()
        )
    )
    private val parent = schema.getType("Parent") as GraphQLInputObjectType
    private val adapter = InputDataValidator.InputDataAdapter { (it as? InputWrapper)?.data }

    private data class InputWrapper(val data: Map<String, Any?>)

    private enum class Color { RED }

    @Test
    fun `literal and programmatic defaults permit omission but never explicit null`() {
        val type = GraphQLInputObjectType.newInputObject().name("Defaults")
            .field(
                GraphQLInputObjectField.newInputObjectField().name("literal")
                    .type(GraphQLNonNull.nonNull(Scalars.GraphQLInt))
                    .defaultValueLiteral(IntValue.newIntValue(5.toBigInteger()).build())
            )
            .field(
                GraphQLInputObjectField.newInputObjectField().name("programmatic")
                    .type(GraphQLNonNull.nonNull(Scalars.GraphQLInt))
                    .defaultValueProgrammatic(7)
            )
            .build()

        assertTrue(InputDataValidator.validateAndCopy(type, emptyMap(), adapter).isEmpty())
        for (field in listOf("literal", "programmatic")) {
            val error = assertThrows<IllegalStateException> {
                InputDataValidator.validateFields(type, mapOf(field to null))
            }
            assertEquals("Field Defaults.$field is required", error.message)
        }
    }

    @Test
    fun `nested validation preserves raw presence and detaches collections`() {
        val child = mutableMapOf<String, Any?>("required" to "value")
        val row = mutableListOf<Int?>(1)
        val children = mutableListOf(child)
        val data = mapOf(
            "children" to children,
            "child" to InputWrapper(child),
            "matrix" to listOf(row),
            "loose" to listOf(null, listOf(null, 2))
        )

        val copy = InputDataValidator.validateAndCopy(parent, data, adapter)
        child["required"] = null
        row[0] = null
        children.clear()

        assertEquals(mapOf("required" to "value"), copy["child"])
        assertEquals(listOf(mapOf("required" to "value")), copy["children"])
        assertEquals(listOf(listOf(1)), copy["matrix"])
        assertEquals(listOf(null, listOf(null, 2)), copy["loose"])
        assertFalse((copy["child"] as Map<*, *>).containsKey("defaulted"))
        assertThrows<UnsupportedOperationException> { (copy as MutableMap)["matrix"] = null }
    }

    @Test
    fun `validation leaves scalar and enum representations for the existing normalizer`() {
        val json = mapOf("payload" to listOf(null, mapOf("value" to null)))

        val copy = InputDataValidator.validateAndCopy(
            parent,
            mapOf("matrix" to emptyList<Int>(), "color" to Color.RED, "json" to json),
            adapter
        )

        assertSame(Color.RED, copy["color"])
        assertSame(json, copy["json"])
    }

    @Test
    fun `nested omissions and nullability failures identify the full field path`() {
        val invalidValues = listOf(
            mapOf("child" to emptyMap<String, Any?>()) to "Parent.child.required",
            mapOf("child" to mapOf("required" to null)) to "Parent.child.required",
            mapOf("children" to listOf(null)) to "Parent.children[0]",
            mapOf("matrix" to listOf(null)) to "Parent.matrix[0]",
            mapOf("matrix" to listOf(listOf(null))) to "Parent.matrix[0][0]",
            mapOf("matrix" to listOf("not a list")) to "Parent.matrix[0]",
            mapOf("child" to "not an input") to "Parent.child"
        )
        for ((values, path) in invalidValues) {
            val error = assertThrows<IllegalStateException> {
                InputDataValidator.validateAndCopy(parent, mapOf("matrix" to emptyList<Int>()) + values, adapter)
            }
            assertTrue(error.message.orEmpty().contains(path), error.message)
        }
        assertThrows<IllegalStateException> { InputDataValidator.validateFields(parent, emptyMap()) }
        assertThrows<IllegalStateException> { InputDataValidator.validateFields(parent, mapOf("matrix" to null)) }
    }

    @Test
    fun `nested oneOf counts supplied fields including explicit null`() {
        val invalidChoices = listOf(
            emptyMap(),
            mapOf("byName" to null),
            mapOf("byName" to "name", "byCount" to null)
        )
        for (choice in invalidChoices) {
            val error = assertThrows<IllegalStateException> {
                InputDataValidator.validateAndCopy(parent, mapOf("matrix" to emptyList<Int>(), "choice" to choice), adapter)
            }
            assertTrue(error.message.orEmpty().contains("Parent.choice"), error.message)
        }
        val copy = InputDataValidator.validateAndCopy(
            parent,
            mapOf("matrix" to emptyList<Int>(), "choice" to mapOf("byName" to "name")),
            adapter
        )
        assertEquals(mapOf("byName" to "name"), copy["choice"])
    }
}
