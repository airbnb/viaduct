package viaduct.api.internal

import graphql.GraphQLContext
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.scalars.ExtendedScalars
import graphql.schema.Coercing
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.InputValueWithState
import java.lang.reflect.InvocationTargetException
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.api.globalid.GlobalID
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.executionContext
import viaduct.api.mocks.testGlobalId
import viaduct.api.reflect.isPresent
import viaduct.api.testschema.ApiTestSchema
import viaduct.api.testschema.E1
import viaduct.api.testschema.Input1
import viaduct.api.testschema.Input2
import viaduct.api.testschema.Input3
import viaduct.api.testschema.InputWithGlobalIDs
import viaduct.api.testschema.O1
import viaduct.api.testschema.O2
import viaduct.api.testschema.O2_ArgumentedField_Arguments
import viaduct.api.testschema.TestUser
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.gj
import viaduct.errors.FrameworkException
import viaduct.errors.TenantUsageException
import viaduct.mapping.graphql.IR

class InputLikeBaseTest {
    private val gqlSchema = ApiTestSchema.schema
    private val internalContext = MockInternalContext.create(gqlSchema, "viaduct.api.testschema")
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

        val resolvedGqlType = gqlType ?: gqlSchema.schema.getTypeAs(cls.simpleName!!)
        return ctor.newInstance(internalContext, map, resolvedGqlType) as T
    }

    // ===== @oneOf validation =====

    /** A @oneOf GraphQLInputObjectType named [name] with String fields [a] and [b]. */
    private fun oneOfType(
        name: String,
        a: String = "byId",
        b: String = "byName"
    ): GraphQLInputObjectType =
        GraphQLInputObjectType.Builder()
            .name(name)
            .field(GraphQLInputObjectField.Builder().name(a).type(graphql.Scalars.GraphQLString).build())
            .field(GraphQLInputObjectField.Builder().name(b).type(graphql.Scalars.GraphQLString).build())
            .withDirective(graphql.Directives.OneOfDirective)
            .build()

    /** Minimal InputLikeBase exposing the framework-error validation entry point. */
    private inner class OneOfTestInput(
        override val inputData: Map<String, Any?>,
        override val graphQLInputObjectType: GraphQLInputObjectType
    ) : InputLikeBase() {
        override val context: InternalContext = internalContext

        fun validate() = validateInputDataAndThrowAsFrameworkError()
    }

    private inner class DefaultValueTestInput(
        override val graphQLInputObjectType: GraphQLInputObjectType,
        override val inputData: Map<String, Any?> = emptyMap()
    ) : InputLikeBase() {
        override val context: InternalContext = internalContext

        fun <T> field(name: String): T = get(name)

        fun validate() = validateInputDataAndThrowAsFrameworkError()
    }

    @Test
    fun `oneOf -- passes with exactly one field set`() {
        assertDoesNotThrow {
            OneOfTestInput(mapOf("byId" to "1"), oneOfType("Filter")).validate()
        }
    }

    @Test
    fun `oneOf -- throws when no field set`() {
        val e = assertThrows<FrameworkException> {
            OneOfTestInput(emptyMap(), oneOfType("Filter")).validate()
        }
        assertTrue(e.message!!.contains("Exactly one field must be set"))
    }

    @Test
    fun `oneOf -- throws when more than one field set`() {
        val e = assertThrows<FrameworkException> {
            OneOfTestInput(mapOf("byId" to "1", "byName" to "x"), oneOfType("Filter")).validate()
        }
        assertTrue(e.message!!.contains("Exactly one field must be set"))
    }

    @Test
    fun `oneOf -- throws when two keys supplied even if all values are null`() {
        val e = assertThrows<FrameworkException> {
            OneOfTestInput(mapOf("byId" to null, "byName" to null), oneOfType("Filter")).validate()
        }
        assertTrue(e.message!!.contains("Exactly one field must be set"))
    }

    @Test
    fun `oneOf -- throws when one non-null and one explicit-null key supplied`() {
        // graphql-java counts supplied keys, not non-null values, so two keys is a violation even
        // though only one value is non-null.
        val e = assertThrows<FrameworkException> {
            OneOfTestInput(mapOf("byId" to "1", "byName" to null), oneOfType("Filter")).validate()
        }
        assertTrue(e.message!!.contains("Exactly one field must be set"))
    }

    @Test
    fun `oneOf -- throws when the single supplied key has a null value`() {
        val e = assertThrows<FrameworkException> {
            OneOfTestInput(mapOf("byId" to null), oneOfType("Filter")).validate()
        }
        assertTrue(e.message!!.contains("must have a non-null value"))
        assertTrue(e.message!!.contains("byId"))
    }

    @Test
    fun `test init via Builder -- throws when missing non-nullable fields without default value`() {
        assertThrows<TenantUsageException> {
            Input1.Builder(executionContext).build()
        }
        assertThrows<TenantUsageException> {
            Input1.Builder(executionContext).nonNullEnumFieldWithDefault(E1.A).build()
        }
    }

    @Test
    fun `test init via constructor -- can construct with missing non-nullable defaulted fields`() {
        val inp = assertDoesNotThrow {
            mk<Input1>(mapOf("nonNullStringField" to "test"))
        }

        // verify that fields with default values, which were not provided at construction time, are materialized by the grt getters
        assertTrue(inp.isPresent(Input1.Fields.enumFieldWithDefault))
        assertTrue(inp.isPresent(Input1.Fields.nonNullEnumFieldWithDefault))
        assertEquals(E1.A, inp.enumFieldWithDefault)
        assertEquals(E1.A, inp.nonNullEnumFieldWithDefault)
    }

    @Test
    fun `test init via constructor -- throws when missing non-nullable fields`() {
        val err = assertThrows<InvocationTargetException> {
            mk<Input1>(emptyMap())
        }
        assertTrue(err.targetException is FrameworkException)
    }

    @Test
    fun `test init via constructor -- throws when missing non-nullable fields are set to explicit null`() {
        val err = assertThrows<InvocationTargetException> {
            mk<Input1>(mapOf("nonNullStringField" to null))
        }
        assertTrue(err.targetException is FrameworkException)
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
        assertTrue(err.targetException is FrameworkException)
    }

    @Test
    fun `test init via constructor -- throws when a non-nullable field has a null value`() {
        val m = mapOf("nonNullEnumFieldWithDefault" to null)
        val err = assertThrows<InvocationTargetException> { mk<Input1>(m) }
        assertTrue(err.targetException is FrameworkException)
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
        assertTrue(input.isPresent(Input1.Fields.enumFieldWithDefault))
        assertEquals(E1.B, input.enumFieldWithDefault)

        // nonNullEnumFieldWithDefault was not set, so the default is applied
        // `isPresent` should return true because the schema default supplies a value.
        assertTrue(input.isPresent(Input1.Fields.nonNullEnumFieldWithDefault))
        assertEquals(E1.A, input.nonNullEnumFieldWithDefault)
    }

    @Test
    fun `arbitrary precision scalar defaults accept supported literal forms`() {
        val decimal = BigDecimal("12345678901234567890.12345678901234567890")
        val integer = BigInteger("123456789012345678901234567890")
        val inputType = GraphQLInputObjectType.Builder()
            .name("ArbitraryPrecisionDefaults")
            .field(
                GraphQLInputObjectField.Builder()
                    .name("decimal")
                    .type(ExtendedScalars.GraphQLBigDecimal)
                    .defaultValueLiteral(StringValue(decimal.toString()))
                    .build()
            )
            .field(
                GraphQLInputObjectField.Builder()
                    .name("integerFromString")
                    .type(ExtendedScalars.GraphQLBigInteger)
                    .defaultValueLiteral(StringValue("$integer.0"))
                    .build()
            )
            .field(
                GraphQLInputObjectField.Builder()
                    .name("integerFromFloat")
                    .type(ExtendedScalars.GraphQLBigInteger)
                    .defaultValueLiteral(FloatValue(BigDecimal("42.0")))
                    .build()
            )
            .build()
        val input = DefaultValueTestInput(inputType)

        assertEquals(decimal, input.field<BigDecimal>("decimal"))
        assertEquals(integer, input.field<BigInteger>("integerFromString"))
        assertEquals(BigInteger.valueOf(42), input.field<BigInteger>("integerFromFloat"))
    }

    @Test
    fun `programmatic defaults resolve nested inputs lists enums and temporal scalars`() {
        val enumType = gqlSchema.schema.getTypeAs<GraphQLEnumType>("E1")
        val input2Type = GraphQLInputObjectType.newInputObject().name("Input2")
            .field(
                GraphQLInputObjectField.newInputObjectField().name("stringField")
                    .type(graphql.Scalars.GraphQLString).defaultValueProgrammatic("nested")
            )
            .build()
        val parseCount = AtomicInteger()
        val dateTimeType = GraphQLScalarType.newScalar().name("DateTime")
            .coercing(object : Coercing<Instant, String> {
                override fun parseValue(
                    input: Any,
                    graphQLContext: GraphQLContext,
                    locale: Locale
                ): Instant {
                    parseCount.incrementAndGet()
                    return Instant.parse(input as String)
                }
            }).build()
        val instant = Instant.parse("2024-01-15T10:30:00Z")
        val inputType = GraphQLInputObjectType.newInputObject()
            .name("ProgrammaticDefaults")
            .field(
                GraphQLInputObjectField.newInputObjectField().name("nested").type(input2Type)
                    .defaultValueProgrammatic(emptyMap<String, Any?>())
            )
            .field(
                GraphQLInputObjectField.newInputObjectField().name("enums").type(GraphQLList.list(enumType))
                    .defaultValueProgrammatic(listOf("A", "B"))
            )
            .field(
                GraphQLInputObjectField.newInputObjectField().name("dateTime").type(dateTimeType)
                    .defaultValueProgrammatic(instant.toString())
            )
            .field(
                GraphQLInputObjectField.newInputObjectField().name("realDateTime").type(ExtendedScalars.DateTime)
                    .defaultValueProgrammatic(instant.toString())
            )
            .field(
                GraphQLInputObjectField.newInputObjectField().name("required")
                    .type(GraphQLNonNull.nonNull(graphql.Scalars.GraphQLString))
                    .defaultValueProgrammatic("fallback")
            )
            .build()
        val input = DefaultValueTestInput(inputType)

        input.validate()
        assertTrue(input.isFieldPresent("nested"))
        assertEquals("nested", input.field<Input2>("nested").stringField)
        assertEquals(listOf(E1.A, E1.B), input.field<List<E1>>("enums"))
        assertEquals(instant, input.field<Instant>("dateTime"))
        assertEquals(1, parseCount.get())
        assertEquals(instant, input.field<Instant>("realDateTime"))
        assertEquals("fallback", input.field<String>("required"))
        assertTrue(input.inputData.isEmpty())

        val explicitNull = DefaultValueTestInput(inputType, mapOf("nested" to null, "required" to null))
        assertTrue(explicitNull.isFieldPresent("nested"))
        assertNull(explicitNull.field<Input2?>("nested"))
        assertThrows<FrameworkException> { explicitNull.validate() }
    }

    @Test
    fun `internally coerced defaults bypass scalar parsing`() {
        val parseCount = AtomicInteger()
        val dateTimeType = GraphQLScalarType.newScalar().name("DateTime")
            .coercing(object : Coercing<Instant, String> {
                override fun parseValue(
                    input: Any,
                    graphQLContext: GraphQLContext,
                    locale: Locale
                ): Instant {
                    parseCount.incrementAndGet()
                    error("Internal values must not be parsed again")
                }
            }).build()
        val instant = Instant.parse("2024-01-15T10:30:00Z")

        val value = defaultValueToIR(InputValueWithState.newInternalValue(instant), dateTimeType, gqlSchema)

        assertEquals(instant, (value as IR.Value.Time).instant)
        assertEquals(0, parseCount.get())
    }

    @Test
    fun `synthetic argument type materializes programmatic and literal defaults`() {
        val field = GraphQLFieldDefinition.newFieldDefinition().name("field")
            .type(graphql.Scalars.GraphQLString)
            .argument(
                GraphQLArgument.newArgument().name("programmatic").type(graphql.Scalars.GraphQLString)
                    .defaultValueProgrammatic("external")
            )
            .argument(
                GraphQLArgument.newArgument().name("literal").type(graphql.Scalars.GraphQLInt)
                    .defaultValueLiteral(IntValue(BigInteger.valueOf(7)))
            )
            .build()
        val schema = EngineSchema(
            GraphQLSchema.newSchema().query(GraphQLObjectType.newObject().name("Query").field(field)).build()
        )
        val inputType = InputTypeFactory.argumentsInputType("Query_Field_Arguments", "Query", "field", schema)
        val input = DefaultValueTestInput(inputType)

        assertTrue(input.isFieldPresent("programmatic"))
        assertEquals("external", input.field<String>("programmatic"))
        assertEquals(7, input.field<Int>("literal"))
        assertNull(DefaultValueTestInput(inputType, mapOf("programmatic" to null)).field<String?>("programmatic"))
    }

    @Test
    fun `test unwrap values via Builder`() {
        val input = Input1.Builder(executionContext).nonNullStringField("test")
            .intField(1)
            .inputField(null)
            .build()

        // verify default values
        assertTrue(input.isPresent(Input1.Fields.enumFieldWithDefault))
        assertTrue(input.isPresent(Input1.Fields.nonNullEnumFieldWithDefault))
        assertEquals(E1.A, input.enumFieldWithDefault)
        assertEquals(E1.A, input.nonNullEnumFieldWithDefault)
        // verify set values
        assertEquals("test", input.nonNullStringField)
        assertEquals(1, input.intField)
        // verify default null values
        assertFalse(input.isPresent(Input1.Fields.stringField))
        assertNull(input.stringField)
        assertFalse(input.isPresent(Input1.Fields.listField))
        assertNull(input.listField)
        // verify set null values
        assertTrue(input.isPresent(Input1.Fields.inputField))
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
        assertFalse(input1.isPresent(Input1.Fields.stringField))
        // verify input2 fields
        assertTrue(input2.isPresent(Input1.Fields.stringField))
        assertEquals("test toBuilder", input2.stringField)
        assertTrue(input2.isPresent(Input1.Fields.intField))
        assertEquals(1, input2.intField)
        assertEquals("test", input2.nonNullStringField)
        assertTrue(input2.isPresent(Input1.Fields.inputField))
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
        assertTrue(input.isPresent(Input1.Fields.stringField))
        assertEquals("test", input.stringField)
        assertTrue(input.isPresent(Input1.Fields.intField))
        assertEquals(1, input.intField)

        // verify unset values
        assertFalse(input.isPresent(Input1.Fields.listField))
        assertNull(input.listField)
        assertFalse(input.isPresent(Input1.Fields.nestedListField))
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
        val coord = (O2.Reflection.name to O2.Fields.argumentedField.name).gj
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

        // check field presence via the public InputLike.isPresent API: provided args report present,
        // defaulted args report present after defaults are applied; other unset args report absent.
        assertTrue(argumentsInput.isPresent(O2_ArgumentedField_Arguments.Fields.stringArg))
        assertTrue(argumentsInput.isPresent(O2_ArgumentedField_Arguments.Fields.inputArg))
        assertTrue(argumentsInput.isPresent(O2_ArgumentedField_Arguments.Fields.intArgWithDefault))
        assertFalse(argumentsInput.isPresent(O2_ArgumentedField_Arguments.Fields.idArg))

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
        assertThrows<FrameworkException> { input.listField }
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
        val id2 = GlobalID(O1.Reflection, "b")
        val id3 = GlobalID(O2.Reflection, "1")
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
        val id2 = GlobalID(TestUser.Reflection, "b")

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
