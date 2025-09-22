package viaduct.api.internal

import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import java.lang.reflect.InvocationTargetException
import java.util.Locale.getDefault
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.ViaductFrameworkException
import viaduct.api.ViaductTenantUsageException
import viaduct.api.mocks.MockGlobalID
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.executionContext
import viaduct.api.schemautils.SchemaUtils
import viaduct.api.testschema.E1
import viaduct.api.testschema.Input1
import viaduct.api.testschema.Input2
import viaduct.api.testschema.Input3
import viaduct.api.testschema.InputWithGlobalIDs
import viaduct.api.testschema.O1
import viaduct.api.testschema.O2
import viaduct.api.testschema.O2_ArgumentedField_Arguments

class InputLikeBaseTest {
    private val gqlSchema = SchemaUtils.getSchema()
    private val internalContext = MockInternalContext.mk(gqlSchema, "viaduct.api.testschema")
    private val executionContext = internalContext.executionContext

    private val inputConstructor = Input1::class.java.declaredConstructors.first {
        it.parameterCount == 3 &&
            it.parameterTypes[0] == InternalContext::class.java &&
            it.parameterTypes[1] == Map::class.java &&
            it.parameterTypes[2] == GraphQLInputObjectType::class.java
    }.apply {
        isAccessible = true
    }

    @Test
    fun `test init via Builder missing required fields throw`() {
        assertThrows<ViaductTenantUsageException> {
            Input1.Builder(executionContext).build()
        }
        assertThrows<ViaductTenantUsageException> {
            Input1.Builder(executionContext).nonNullEnumFieldWithDefault(E1.A).build()
        }
    }

    @Test
    fun `test init via constructor missing default value throw`() {
        val m = mapOf(
            "nonNullStringField" to "test",
        )
        val type = gqlSchema.schema.getTypeAs<GraphQLInputObjectType>("Input1")
        assertThrows<InvocationTargetException> { inputConstructor.newInstance(internalContext, m, type) }
        try {
            inputConstructor.newInstance(internalContext, m, type)
        } catch (e: InvocationTargetException) {
            assertTrue(e.targetException is ViaductFrameworkException)
        }
    }

    @Test
    fun `test init via constructor missing required fields throw`() {
        val m = mapOf(
            "enumFieldWithDefault" to "A",
            "nonNullEnumFieldWithDefault" to "A",
        )
        val type = gqlSchema.schema.getTypeAs<GraphQLInputObjectType>("Input1")
        assertThrows<InvocationTargetException> { inputConstructor.newInstance(internalContext, m, type) }
        try {
            inputConstructor.newInstance(internalContext, m, type)
        } catch (e: InvocationTargetException) {
            assertTrue(e.targetException is ViaductFrameworkException)
        }
    }

    @Test
    fun `test default values via Builder`() {
        val input = Input1.Builder(executionContext)
            .nonNullStringField("test")
            .enumFieldWithDefault(E1.B)
            .listField(listOf(E1.B))
            .nestedListField(listOf(listOf(E1.B)))
            .inputField(Input2.Builder(internalContext.executionContext).stringField("input2 test").build())
            .build()

        assertTrue(input.isPresent("enumFieldWithDefault"))
        assertEquals(E1.B, input.enumFieldWithDefault)
        assertTrue(input.isPresent("nonNullEnumFieldWithDefault"))
        assertEquals(E1.A, input.nonNullEnumFieldWithDefault)
        assertTrue(input.isPresent("listField"))
        assertEquals(listOf(E1.B), input.listField)
        assertTrue(input.isPresent("nestedListField"))
        assertEquals(listOf(listOf(E1.B)), input.nestedListField)
        assertTrue(input.isPresent("inputField"))
        assertTrue(input.inputField is Input2)
        assertEquals("input2 test", input.inputField!!.stringField)
    }

    @Test
    fun `test unwrap values via Builder`() {
        val input = Input1.Builder(executionContext).nonNullStringField("test")
            .intField(1)
            .inputField(null)
            .build()

        // verify default values
        assertEquals(E1.A, input.enumFieldWithDefault)
        assertEquals(E1.A, input.nonNullEnumFieldWithDefault)
        // verify set values
        assertEquals("test", input.nonNullStringField)
        assertEquals(1, input.intField)
        // verify default null values
        assertFalse(input.isPresent("stringField"))
        assertNull(input.stringField)
        assertFalse(input.isPresent("listField"))
        assertNull(input.listField)
        // verify set null values
        assertTrue(input.isPresent("inputField"))
        assertNull(input.inputField)
    }

    @Test
    fun `test toBuilder`() {
        val input1 = Input1.Builder(executionContext).nonNullStringField("test")
            .intField(1)
            .inputField(null)
            .build()
        val input2 = input1.toBuilder()
            .stringField("test toBuilder")
            .build()
        // stringField is unchanged
        assertFalse(input1.isPresent("stringField"))
        // verify input2 fields
        assertTrue(input2.isPresent("stringField"))
        assertEquals("test toBuilder", input2.stringField)
        assertTrue(input2.isPresent("intField"))
        assertEquals(1, input2.intField)
        assertEquals("test", input2.nonNullStringField)
        assertTrue(input2.isPresent("inputField"))
        assertNull(input2.inputField)
    }

    @Test
    fun `test init via reflection with values`() {
        val args = mapOf(
            "enumFieldWithDefault" to E1.B,
            "nonNullEnumFieldWithDefault" to E1.B,
            "nonNullStringField" to "test",
            "stringField" to "test",
            "intField" to 1,
        )
        val input = inputConstructor.newInstance(
            internalContext,
            args,
            gqlSchema.schema.getTypeAs<GraphQLInputObjectType>("Input1")
        ) as Input1

        // verify set values via backing map
        assertEquals(E1.B, input.enumFieldWithDefault)
        assertEquals(E1.B, input.nonNullEnumFieldWithDefault)
        assertEquals("test", input.nonNullStringField)
        assertTrue(input.isPresent("stringField"))
        assertEquals("test", input.stringField)
        assertTrue(input.isPresent("intField"))
        assertEquals(1, input.intField)

        // verify unset values
        assertFalse(input.isPresent("listField"))
        assertNull(input.listField)
        assertFalse(input.isPresent("nestedListField"))
        assertNull(input.inputField)
    }

    @Test
    fun `test init via reflection with raw values`() {
        val args = mapOf(
            "enumFieldWithDefault" to "B",
            "nonNullEnumFieldWithDefault" to "B",
            "stringField" to "test",
            "intField" to 1,
            "nonNullStringField" to "test",
            "listField" to listOf("A"),
            "nestedListField" to listOf(listOf("A")),
            "inputField" to mapOf("stringField" to "input2 test"),
        )
        val input = inputConstructor.newInstance(
            internalContext,
            args,
            gqlSchema.schema.getTypeAs<GraphQLInputObjectType>("Input1")
        ) as Input1

        assertEquals(E1.B, input.enumFieldWithDefault)
        assertEquals(E1.B, input.nonNullEnumFieldWithDefault)
        assertEquals("test", input.stringField)
        assertEquals(1, input.intField)
        assertEquals("test", input.nonNullStringField)
        assertEquals(listOf(E1.A), input.listField)
        assertEquals(listOf(listOf(E1.A)), input.nestedListField)
        assertTrue(input.inputField is Input2)
        assertEquals("input2 test", input.inputField!!.stringField)
    }

    @Test
    fun `test arguments type`() {
        val argumentName = "O2_ArgumentedField_Arguments"
        val typeName = argumentName.split("_").first()
        val fieldName = argumentName.split("_")[1].replaceFirstChar { it.lowercase(getDefault()) }
        val arguments = gqlSchema.schema.getObjectType(typeName).getField(fieldName).arguments
        val fields = arguments.map {
            val builder = GraphQLInputObjectField.Builder()
                .name(it.name)
                .type(it.type)
            if (it.hasSetDefaultValue() && it.argumentDefaultValue.isLiteral) {
                val v = it.argumentDefaultValue.value as graphql.language.Value<*>
                builder.defaultValueLiteral(v)
            }
            builder.build()
        }
        val inputObject = GraphQLInputObjectType.Builder()
            .name(argumentName)
            .fields(fields)
            .build()

        val args = mapOf(
            "stringArg" to "test",
            "intArgWithDefault" to 2,
            "inputArg" to mapOf(
                "enumFieldWithDefault" to "A",
                "nonNullEnumFieldWithDefault" to "A",
                "nonNullStringField" to "a",
            ),
        )

        val inputConstructor = O2_ArgumentedField_Arguments::class.java.declaredConstructors.first {
            it.parameterCount == 3 &&
                it.parameterTypes[0] == InternalContext::class.java &&
                it.parameterTypes[1] == Map::class.java &&
                it.parameterTypes[2] == GraphQLInputObjectType::class.java
        }
        inputConstructor.isAccessible = true
        val argumentsInput = inputConstructor.newInstance(internalContext, args, inputObject) as O2_ArgumentedField_Arguments

        assertEquals("test", argumentsInput.stringArg)
        assertEquals(2, argumentsInput.intArgWithDefault)
        assertTrue(argumentsInput.inputArg is Input1)
        assertEquals(E1.A, argumentsInput.inputArg!!.enumFieldWithDefault)
    }

    @Test
    fun `test wrap is lazy and throw exception when get`() {
        val args = mapOf(
            "enumFieldWithDefault" to "B",
            "nonNullEnumFieldWithDefault" to "B",
            "nonNullStringField" to "test",
            "listField" to 1,
        )
        val input = inputConstructor.newInstance(
            internalContext,
            args,
            gqlSchema.schema.getTypeAs<GraphQLInputObjectType>("Input1")
        ) as Input1

        assertNotNull(input)
        assertThrows<ViaductFrameworkException> { input.listField }
    }

    @Test
    fun `test default input value as map`() {
        val a = Input3.Builder(executionContext).build()
        assertTrue(a.inputField is Input2)
        assertEquals("defaultStringField", a.inputField!!.stringField)
    }

    @Test
    fun `test wrapping and unwrapping GlobalID fields`() {
        val id = "a"
        val id2 = MockGlobalID(O1.Reflection, "b")
        val id3 = MockGlobalID(O2.Reflection, "1")
        val ids = listOf(listOf(null, id3))

        val input = InputWithGlobalIDs.Builder(executionContext)
            .id(id)
            .id2(id2)
            .ids(ids)
            .build()

        assertEquals(input.id, id)
        assertEquals(input.id2, id2)
        assertEquals(
            mapOf(
                "id" to id,
                "id2" to id2.toString(),
                "ids" to listOf(listOf(null, id3.toString()))
            ),
            input.inputData
        )
    }
}
