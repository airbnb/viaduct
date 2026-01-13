package viaduct.api.internal

import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import java.lang.reflect.InvocationTargetException
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.api.ViaductFrameworkException
import viaduct.api.ViaductTenantUsageException
import viaduct.api.globalid.GlobalIDImpl
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.executionContext
import viaduct.api.mocks.testGlobalId
import viaduct.api.schemautils.SchemaUtils
import viaduct.api.testschema.E1
import viaduct.api.testschema.Input1
import viaduct.api.testschema.Input2
import viaduct.api.testschema.Input3
import viaduct.api.testschema.InputWithGlobalIDs
import viaduct.api.testschema.O1
import viaduct.api.testschema.O2
import viaduct.api.testschema.O2_ArgumentedField_Arguments
import viaduct.api.testschema.TestUser
import viaduct.engine.api.gj

class InputLikeBaseTest {
    private val gqlSchema = SchemaUtils.getSchema()
    private val internalContext = MockInternalContext.mk(gqlSchema, "viaduct.api.testschema")
    private val executionContext = internalContext.executionContext

    private inline fun <reified T : InputLikeBase> mk(
        map: Map<String, Any?>,
        gqlType: GraphQLInputObjectType? = null
    ): T {
        val cls = T::class
        val ctor = cls.java.declaredConstructors.first {
            it.parameterCount == 3 &&
                it.parameterTypes[0] == InternalContext::class.java &&
                it.parameterTypes[1] == Map::class.java &&
                it.parameterTypes[2] == GraphQLInputObjectType::class.java
        }.apply {
            isAccessible = true
        }

        val gqlType = gqlType ?: gqlSchema.schema.getTypeAs(cls.simpleName!!)
        return ctor.newInstance(internalContext, map, gqlType) as T
    }

    @Test
    fun `test init via Builder -- throws when missing non-nullable fields without default value`() {
        assertThrows<ViaductTenantUsageException> {
            Input1.Builder(executionContext).build()
        }
        assertThrows<ViaductTenantUsageException> {
            Input1.Builder(executionContext).nonNullEnumFieldWithDefault(E1.A).build()
        }
    }

    @Test
    fun `test init via constructor -- can construct with missing non-nullable defaulted fields`() {
        val inp = assertDoesNotThrow {
            mk<Input1>(mapOf("nonNullStringField" to "test"))
        }

        // verify that fields with default values, which were not provided at construction time, are materialized by the grt getters
        assertFalse(inp.isPresent("enumFieldWithDefault"))
        assertFalse(inp.isPresent("nonNullEnumFieldWithDefault"))
        assertEquals(E1.A, inp.enumFieldWithDefault)
        assertEquals(E1.A, inp.nonNullEnumFieldWithDefault)
    }

    @Test
    fun `test init via constructor -- throws when missing non-nullable fields`() {
        val err = assertThrows<InvocationTargetException> {
            mk<Input1>(emptyMap())
        }
        assertTrue(err.targetException is ViaductFrameworkException)
    }

    @Test
    fun `test init via constructor -- throws when missing non-nullable fields are set to explicit null`() {
        val err = assertThrows<InvocationTargetException> {
            mk<Input1>(mapOf("nonNullStringField" to null))
        }
        assertTrue(err.targetException is ViaductFrameworkException)
    }

    @Test
    fun `test init via constructor -- throws when missing non-nullable defaulted fields are set to explicit null`() {
        val err = assertThrows<InvocationTargetException> {
            mk<Input1>(
                mapOf(
                    // non-null field without default
                    "nonNullStringField" to "test",
                    // non-null field with default, set to explicit null. This should cause an exception to be thrown.
                    "nonNullEnumFieldWithDefault" to null,
                )
            )
        }
        assertTrue(err.targetException is ViaductFrameworkException)
    }

    @Test
    fun `test init via constructor -- throws when a non-nullable field has a null value`() {
        val m = mapOf("nonNullEnumFieldWithDefault" to null)
        val err = assertThrows<InvocationTargetException> { mk<Input1>(m) }
        assertTrue(err.targetException is ViaductFrameworkException)
    }

    @Test
    fun `test default values via Builder`() {
        val input = Input1.Builder(executionContext)
            // non-nullable field must be set
            .nonNullStringField("test")
            .enumFieldWithDefault(E1.B)
            .build()

        // enumFieldWithDefault was set with a value that overrides the default
        // `isPresent` should return true because it was set, and the getter should return the set value
        assertTrue(input.isPresent("enumFieldWithDefault"))
        assertEquals(E1.B, input.enumFieldWithDefault)

        // nonNullEnumFieldWithDefault was not set, so the default is applied
        // `isPresent` should return false because it was not set, and the getter should return the set value
        assertFalse(input.isPresent("nonNullEnumFieldWithDefault"))
        assertEquals(E1.A, input.nonNullEnumFieldWithDefault)
    }

    @Test
    fun `test unwrap values via Builder`() {
        val input = Input1.Builder(executionContext).nonNullStringField("test")
            .intField(1)
            .inputField(null)
            .build()

        // verify default values
        assertFalse(input.isPresent("enumFieldWithDefault"))
        assertFalse(input.isPresent("nonNullEnumFieldWithDefault"))
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
            "enumFieldWithDefault" to E1.B.name,
            "nonNullEnumFieldWithDefault" to E1.B.name,
            "nonNullStringField" to "test",
            "stringField" to "test",
            "intField" to 1,
        )
        val input = mk<Input1>(args)

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
        val input = mk<Input1>(args)

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
        val argumentName = O2_ArgumentedField_Arguments::class.simpleName!!
        val coord = (O2.Reflection.name to O2.Reflection.Fields.argumentedField.name).gj
        val arguments = gqlSchema.schema.getFieldDefinition(coord).arguments
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
            "inputArg" to mapOf(
                "enumFieldWithDefault" to "A",
                "nonNullEnumFieldWithDefault" to "A",
                "nonNullStringField" to "a",
            ),
        )
        val argumentsInput = mk<O2_ArgumentedField_Arguments>(args, inputObject)

        // check field presence
        inputObject.fields.forEach { f ->
            val expectPresent = f.name in args
            assertEquals(expectPresent, argumentsInput.isPresent(f.name), f.name)
        }

        assertEquals("test", argumentsInput.stringArg)
        assertEquals(1, argumentsInput.intArgWithDefault)

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
        val input = mk<Input1>(args)

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
    fun `GlobalID wrapping`() {
        val id = "a"
        val id2 = GlobalIDImpl(O1.Reflection, "b")
        val id3 = GlobalIDImpl(O2.Reflection, "1")
        val ids = listOf(listOf(null, id3))

        val input = InputWithGlobalIDs.Builder(executionContext)
            .id(id)
            .id2(id2)
            .ids(ids)
            .build()

        assertEquals(input.id, id)
        assertEquals(id2.type.name, input.id2.type.name)
        assertEquals(id2.internalID, input.id2.internalID)
        assertEquals(
            mapOf(
                "id" to id,
                "id2" to O1.Reflection.testGlobalId("b"),
                "ids" to listOf(listOf(null, O2.Reflection.testGlobalId("1")))
            ),
            input.inputData
        )
    }

    @Test
    fun `GlobalID wrapping -- nested`() {
        val id1 = "a"
        val id2 = GlobalIDImpl(TestUser.Reflection, "b")

        val inp = Input1.Builder(executionContext)
            // non-null field
            .nonNullStringField("")
            .inputField(
                Input2.Builder(executionContext)
                    .id1(id1)
                    .id2(id2)
                    .build()
            )
            .build()

        assertEquals(id1, inp.inputField?.id1)
        assertEquals(id2, inp.inputField?.id2)
    }

    @Test
    fun `DateTime wrapping`() {
        val inp = Input2.Builder(executionContext)
            .dateTimeField(Instant.MAX)
            .build()
        assertEquals(Instant.MAX, inp.dateTimeField)
    }

    @Test
    fun `DateTime wrapping -- nested`() {
        val inp = Input1.Builder(executionContext)
            // non-null field
            .nonNullStringField("")
            .inputField(
                Input2.Builder(executionContext)
                    .dateTimeField(Instant.MAX)
                    .build()
            )
            .build()

        assertEquals(Instant.MAX, inp.inputField?.dateTimeField)
    }
}
