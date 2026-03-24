package viaduct.graphql.schema.graphqljava

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.ScalarValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.InputValueWithState
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ValueConverterTest {
    companion object {
        private val schema = """
            type Query { empty: String }

            # Custom scalars
            scalar Long
            scalar Short
            scalar Date
            scalar DateTime
            scalar Time

            enum EnumA { A B }

            input Input {
              f1: Long
              f2: [Short]
            }

            # Preserve builtin scalars (ID, Float)
            type Keep {
              id: ID
              float: Float
            }

            input SubjectConstants {
              boolean: Boolean = true
              float: Float! = 3.14159
              int: Int = 2
              long: Long = 3
              short: Short = 4
              string: String = "hello"
              rstring: String! = "world"
              longList: [[Long]] = [[1, 2, 3]]
              in: Input = { f1: 1, f2: [2, 3] }
              enumA: EnumA = A
              explicitNull: String = null
            }

            type SubjectConstantsArgs {
              field(
                boolean: Boolean = true,
                float: Float! = 1,
                int: Int = 2,
                long: Long = 3.0,
                short: Short = 4,
                string: String = "hello",
                rstring: String! = "world",
                longList: [[Long]] = [[1, 2, 3]],
                in: Input = { f1: 1, f2: [2, 3] },
                enumA: EnumA = A
              ): String
            }
        """.trimIndent()

        private val NULL_STANDIN = Any()

        /** Extension to get the path key for a field or field arg. */
        private val ViaductSchema.HasDefaultValue.key: String
            get() = when (this) {
                is ViaductSchema.Field -> "${containingDef.name}.$name"
                is ViaductSchema.FieldArg -> "${containingDef.containingDef.name}.${containingDef.name}.$name"
                else -> error("Unexpected type: $this")
            }

        /** Get all fields and field args from Subject* types. */
        private fun ViaductSchema.cases(allElements: Boolean = false): List<ViaductSchema.HasDefaultValue> =
            types.values.filter { it.name.startsWith("Subject") }.flatMap { typeDef ->
                typeDef as ViaductSchema.Record
                typeDef.fields.mapNotNull { field ->
                    if (!allElements && !field.hasEffectiveDefault) return@mapNotNull null
                    field
                } + typeDef.fields.flatMap { field ->
                    field.args.mapNotNull { arg ->
                        if (!allElements && !arg.hasEffectiveDefault) return@mapNotNull null
                        arg
                    }
                }
            }

        /** Helper for comparing Value objects (which use reference equality). */
        private fun assertValueEquals(
            a: Value<*>?,
            b: Value<*>?
        ) {
            when (a) {
                null -> assertNull(b)
                is NullValue, is ScalarValue<*>, is EnumValue -> assert(a.isEqualTo(b))
                is ArrayValue -> {
                    assert(b is ArrayValue)
                    b as ArrayValue
                    assertEquals(a.values.size, b.values.size)
                    a.values.zip(b.values).forEach { (av, bv) -> assert(av == bv) }
                }
                is ObjectValue -> {
                    assert(b is ObjectValue)
                    b as ObjectValue
                    val aMap = a.objectFields.associateBy({ it.name }, { it.value })
                    val bMap = b.objectFields.associateBy({ it.name }, { it.value })
                    assertEquals(aMap.keys, bMap.keys)
                    aMap.forEach { (k, v) -> assert(v == bMap[k]) }
                }
                else -> fail("Unexpected value: $a")
            }
        }
    }

    private lateinit var viaductSchema: ViaductSchema
    private lateinit var defaultValues: Map<String, Any?>
    private lateinit var effectiveDefaultValues: Map<String, Any?>

    @BeforeAll
    fun setup() {
        val registry = readTypes(schema)
        val graphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
        viaductSchema = ViaductSchema.fromGraphQLSchema(graphQLSchema)

        defaultValues = mapOf(
            "SubjectConstants.boolean" to BooleanValue.of(true),
            "SubjectConstants.float" to FloatValue.of(3.14159),
            "SubjectConstants.int" to IntValue.of(2),
            "SubjectConstants.long" to IntValue.of(3),
            "SubjectConstants.short" to IntValue.of(4),
            "SubjectConstants.string" to StringValue.of("hello"),
            "SubjectConstants.rstring" to StringValue.of("world"),
            "SubjectConstants.longList" to ArrayValue(listOf(ArrayValue(listOf(IntValue.of(1), IntValue.of(2), IntValue.of(3))))),
            "SubjectConstants.in" to ObjectValue(
                listOf(
                    ObjectField("f1", IntValue.of(1)),
                    ObjectField("f2", ArrayValue(listOf(IntValue.of(2), IntValue.of(3))))
                )
            ),
            "SubjectConstants.enumA" to EnumValue("A"),
            "SubjectConstants.explicitNull" to NullValue.of(),
            "SubjectConstantsArgs.field.enumA" to EnumValue("A"),
            "SubjectConstantsArgs.field.boolean" to BooleanValue.of(true),
            "SubjectConstantsArgs.field.float" to FloatValue.of(1.0),
            "SubjectConstantsArgs.field.int" to FloatValue.of(2.0),
            "SubjectConstantsArgs.field.long" to IntValue.of(3),
            "SubjectConstantsArgs.field.short" to IntValue.of(4),
            "SubjectConstantsArgs.field.string" to StringValue.of("hello"),
            "SubjectConstantsArgs.field.rstring" to StringValue.of("world"),
            "SubjectConstantsArgs.field.longList" to ArrayValue(listOf(ArrayValue(listOf(IntValue.of(1), IntValue.of(2), IntValue.of(3))))),
            "SubjectConstantsArgs.field.in" to ObjectValue(
                listOf(
                    ObjectField("f1", IntValue.of(1)),
                    ObjectField("f2", ArrayValue(listOf(IntValue.of(2), IntValue.of(3))))
                )
            )
        )

        // Compute effective defaults: add null for nullable fields without explicit default
        effectiveDefaultValues = defaultValues + viaductSchema.cases()
            .filter { !defaultValues.containsKey(it.key) && it.type.isNullable }
            .associate { it.key to null }
    }

    /**
     * Transform Value objects into comparable forms since graphql-java Value.equals()
     * uses reference equality, not value equality.
     * Also handles ViaductSchema.Literal types which are now returned from defaultValue.
     */
    private fun xform(value: Any?): Any? {
        // Handle ViaductSchema.Literal types (returned from defaultValue, effectiveDefaultValue)
        return when (value) {
            null -> value
            is ViaductSchema.NullLiteral -> NULL_STANDIN
            is ViaductSchema.TrueLiteral -> true
            is ViaductSchema.FalseLiteral -> false
            is ViaductSchema.IntLiteral -> value.value.toDouble()
            is ViaductSchema.FloatLiteral -> value.value.toDouble()
            is ViaductSchema.StringLiteral -> "StringValue{${value.value}}"
            is ViaductSchema.EnumLit -> "EnumValue{${value.value}}"
            is ViaductSchema.ListLiteral -> value.map { xform(it) }
            is ViaductSchema.ObjectLiteral -> value.entries.associate { it.key to xform(it.value) }
            // Handle graphql.language.Value types (from test inputs)
            is ArrayValue -> value.values.map { xform(it) }
            is BooleanValue -> value.isValue
            is EnumValue -> "EnumValue{${value.name}}"
            is FloatValue -> value.value.toDouble()
            is IntValue -> value.value.toDouble()
            is NullValue -> NULL_STANDIN
            is ObjectValue -> value.objectFields.associate { it.name to xform(it.value) }
            is StringValue -> "StringValue{${value.value}}"
            is Value<*> -> throw IllegalArgumentException("Unknown graphql.language.Value type: ${value::class.java}")
            else -> value
        }
    }

    private fun assertValuesEqual(
        expected: Any?,
        actual: Any?
    ) {
        assertEquals(xform(expected), xform(actual))
    }

    private fun typeOf(key: String): ViaductSchema.TypeExpr<*> = viaductSchema.cases(allElements = true).find { it.key == key }!!.type

    private fun expr(name: String): ViaductSchema.TypeExpr<*> = viaductSchema.types[name]!!.asTypeExpr()

    @TestFactory
    fun `test default and effective-default functions`(): List<DynamicTest> =
        viaductSchema.cases(allElements = true).map { case ->
            DynamicTest.dynamicTest(case.key) {
                if (defaultValues.containsKey(case.key)) {
                    assert(case.hasDefault)
                    assertValuesEqual(defaultValues[case.key], case.defaultValue)
                } else {
                    assert(!case.hasDefault)
                    assertThrows(NoSuchElementException::class.java) { case.defaultValue }
                }
                if (effectiveDefaultValues.containsKey(case.key)) {
                    assert(case.hasEffectiveDefault)
                    assertValuesEqual(effectiveDefaultValues[case.key], case.effectiveDefaultValue)
                } else {
                    assert(!case.hasEffectiveDefault)
                    assertThrows(NoSuchElementException::class.java) { case.effectiveDefaultValue }
                }
            }
        }

    @TestFactory
    fun `convert literal values`(): List<DynamicTest> {
        val inputs = mapOf(
            "SubjectConstants.boolean" to BooleanValue.of(true),
            "SubjectConstants.float" to FloatValue.of(3.14159),
            "SubjectConstants.int" to IntValue.of(2),
            "SubjectConstants.long" to IntValue.of(3),
            "SubjectConstants.short" to IntValue.of(4),
            "SubjectConstants.string" to StringValue.of("hello"),
            "SubjectConstants.rstring" to StringValue.of("world"),
            "SubjectConstants.longList" to ArrayValue(listOf(ArrayValue((1..3).map(IntValue::of)))),
            "SubjectConstants.in" to ObjectValue(
                listOf(
                    ObjectField("f1", IntValue.of(1)),
                    ObjectField("f2", ArrayValue(listOf(IntValue.of(2), IntValue.of(3))))
                )
            ),
            "SubjectConstants.enumA" to EnumValue.of("A")
        )
        return inputs.map { (coord, literal) ->
            DynamicTest.dynamicTest(coord) {
                val type = typeOf(coord)
                val converted = ValueConverter.convert(type, InputValueWithState.newLiteralValue(literal))
                assertValuesEqual(defaultValues[coord], converted)
            }
        }
    }

    @TestFactory
    fun `convert external values`(): List<DynamicTest> {
        val inputs = mapOf(
            "SubjectConstants.boolean" to true,
            "SubjectConstants.float" to 3.14159,
            "SubjectConstants.int" to 2,
            "SubjectConstants.long" to 3,
            "SubjectConstants.short" to 4,
            "SubjectConstants.string" to "hello",
            "SubjectConstants.rstring" to "world"
        )
        return inputs.map { (coord, external) ->
            DynamicTest.dynamicTest(coord) {
                val type = typeOf(coord)
                val converted = ValueConverter.convert(type, InputValueWithState.newExternalValue(external))
                assertValuesEqual(defaultValues[coord], converted)
            }
        }
    }

    @Test
    fun `externalToLiteral converts scalar types`() {
        fun assertConverts(
            typeName: String,
            value: Any?,
            expected: Value<*>
        ) {
            val type = expr(typeName)
            assertValueEquals(ValueConverter.externalToLiteral(type, value), expected)
        }

        assertConverts("String", null, NullValue.of())
        assertConverts("String", "a", StringValue("a"))
        assertConverts("Boolean", true, BooleanValue(true))
        LocalDate.MAX.let { date ->
            assertConverts("Date", date, StringValue(date.toString()))
        }
        Instant.MAX.let { inst ->
            assertConverts("DateTime", inst, StringValue(inst.toString()))
        }
        assertConverts("Float", 3.14, FloatValue.of(3.14))
        assertConverts("Int", 42, IntValue.of(42))
        assertConverts("Long", 42L, IntValue.of(42))
        assertConverts("Short", 42.toShort(), IntValue.of(42))
        assertConverts("ID", "a", StringValue.of("a"))
        OffsetTime.MAX.let { time ->
            assertConverts("Time", time, StringValue(time.toString()))
        }
    }
}
