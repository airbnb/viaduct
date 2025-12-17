package viaduct.graphql.schema.graphqljava.test

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.schema.InputValueWithState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import viaduct.apiannotations.TestingApi
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.ValueConverter
import viaduct.graphql.schema.graphqljava.test.ValueConverterContract.Companion.schema

/** Test contract for implementations of ValueConverter.
 *  To test such an implementation, subclass this class following the
 *  pattern set in [DefaultValueConverterTest].  Key elements of that
 *  class to note:
 *
 *  * It has "per class" lifecycle and a `@BeforeAll` setup method.
 *    That method needs to initialize [viaductExtendedSchema], [defaultValues],
 *    and [effectiveDefaultValues].  The convenience method
 *    [defaultsToEffectiveDefaults] will initialize [effectiveDefaultValues]
 *    for most usecases.
 *
 *  * [defaultValues] is a Map<String,Any?>, where the keys are paths
 *    to a schema element that has a default value (e.g., `InputType.field`
 *    or `ObjectType.field.arg`).  The values are the default-value
 *    representations we expect the converter to produce.
 *    [effectiveDefaultValues] has all those entries, but has additional
 *    entries for nullable fields and arguments.
 *
 *  * If value-equality of your default-values representation is not
 *    determined by `equals`, then override [xform].
 *
 *  * The [schema] property provides a GraphQL schema that tests almost
 *    every case of [ValueConverter] and its interaction with [GJSchema].
 */
@TestingApi
abstract class ValueConverterContract {
    /** Subclasses initialize this with a GJSchema with a particular value converter to be tested. */
    lateinit var viaductExtendedSchema: ViaductSchema

    /** Subclasses initialize this with expected default values for paths in [schema]. */
    lateinit var defaultValues: Map<String, Any?>

    /** Almost always set by initializing [defaultValues] and calling [defaultsToEffectiveDefaults]. */
    lateinit var effectiveDefaultValues: Map<String, Any?>

    /**
     *  Subclasses initialize to the value converter under test (ie, the one used to
     *  create [viaductExtendedSchema]
     */
    lateinit var valueConverter: ValueConverter

    /**
     *  The tests in this class assume that expected and actual values can
     *  be compared using `equals`.  If that's not the case, then override
     *  this function to transform those values into types that _can_ be
     *  compared using `equals`.
     */
    open fun xform(value: Any?) = value

    /**
     *  Most subclasses can leave this alone.  However, subclasses whose conversion disagrees
     *  with [InputValueWithState.newLiteralValue] may want to override this property.
     */
    open val literalValues: Map<String, Any?> get() = defaultValues

    /**
     *  Most subclasses can leave this alone.  However, subclasses whose conversion disagrees
     *  with [InputValueWithState.newExternalValue] may want to override this property.
     */
    open val externalValues: Map<String, Any?> get() = defaultValues

    /** Convenience function that sets a value for [effectiveDefaultValues] after
     *  values for [viaductExtendedSchema] and [defaultValues] have been set.  For any input-field
     *  and argument that does not have a default value in [defaultValues] and whose type
     *  is nullable will be given a `null` default value.
     */
    fun defaultsToEffectiveDefaults() {
        val nullDefaults = mutableMapOf<String, Any?>()
        for (case in viaductExtendedSchema.cases()) {
            if (!defaultValues.containsKey(case.key) && case.type.isNullable) {
                nullDefaults[case.key] = null
            }
        }
        effectiveDefaultValues = defaultValues + nullDefaults
    }

    companion object {
        val schema =
            """
            type Query { empty: String }
            scalar Long
            scalar Short
            enum EnumA {
              A
              B
            }
            input Input {
              f1: Long
              f2: [Short]
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

        // The rest of this file is implementation details

        private val ViaductSchema.HasDefaultValue.key: String
            get() =
                when (this) {
                    is ViaductSchema.Field -> "${this.containingDef.name}.${this.name}"
                    is ViaductSchema.FieldArg ->
                        "${this.containingDef.containingDef.name}.${this.containingDef.name}.${this.name}"

                    else -> throw IllegalArgumentException("Unexpected type: $this")
                }

        private fun ViaductSchema.cases(allElements: Boolean = false): List<ViaductSchema.HasDefaultValue> =
            this.types.values.filter { it.name.startsWith("Subject") }.flatMap {
                it as ViaductSchema.Record
                it.fields.mapNotNull { field ->
                    if (!allElements && !field.hasEffectiveDefault) return@mapNotNull null
                    field
                } +
                    it.fields.flatMap { field ->
                        field.args.mapNotNull { arg ->
                            if (!allElements && !arg.hasEffectiveDefault) return@mapNotNull null
                            arg
                        }
                    }
            }
    }

    private fun assertPossiblyNestedIsEqual(
        expected: Any?,
        actual: Any?
    ) {
        val xExpected = xform(expected)
        val xActual = xform(actual)
        if (xActual is Map<*, *>) {
            assertEquals(xActual, xExpected as Map<*, *>)
        } else if (xActual is Iterable<*>) {
            assertEquals(xActual, xExpected as Iterable<*>)
        } else {
            assertEquals(xActual, xExpected)
        }
    }

    private fun typeOf(key: String): ViaductSchema.TypeExpr =
        viaductExtendedSchema
            .cases(allElements = true)
            .find { it.key == key }!!
            .let { case ->
                when (case) {
                    is ViaductSchema.TypeDef -> case.asTypeExpr()
                    else -> case.type
                }
            }

    @TestFactory
    fun `test default and effective-default functions for`(): List<DynamicTest> =
        viaductExtendedSchema.cases(allElements = true).map { case ->
            DynamicTest.dynamicTest(case.key) {
                if (defaultValues.containsKey(case.key)) {
                    assert(case.hasDefault)
                    assertPossiblyNestedIsEqual(defaultValues[case.key], case.defaultValue)
                } else {
                    assert(!case.hasDefault)
                    assertThrows(NoSuchElementException::class.java) { case.defaultValue }
                }
                if (effectiveDefaultValues.containsKey(case.key)) {
                    assert(case.hasEffectiveDefault)
                    assertPossiblyNestedIsEqual(effectiveDefaultValues[case.key], case.effectiveDefaultValue)
                } else {
                    assert(!case.hasEffectiveDefault)
                    assertThrows(NoSuchElementException::class.java) { case.effectiveDefaultValue }
                }
            }
        }

    @TestFactory
    fun `convert literal`(): List<DynamicTest> {
        val inputs =
            mapOf(
                "SubjectConstants.boolean" to BooleanValue.of(true),
                "SubjectConstants.float" to FloatValue.of(3.14159),
                "SubjectConstants.int" to IntValue.of(2),
                "SubjectConstants.long" to IntValue.of(3),
                "SubjectConstants.short" to IntValue.of(4),
                "SubjectConstants.string" to StringValue.of("hello"),
                "SubjectConstants.rstring" to StringValue.of("world"),
                "SubjectConstants.longList" to
                    ArrayValue(listOf(ArrayValue((1..3).map(IntValue::of)))),
                "SubjectConstants.in" to
                    ObjectValue(
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
                val converted = valueConverter.convert(type, InputValueWithState.newLiteralValue(literal))
                if (literalValues.containsKey(coord)) {
                    assertPossiblyNestedIsEqual(literalValues[coord]!!, converted)
                } else {
                    assertNull(converted)
                }
            }
        }
    }

    @TestFactory
    fun `convert external`(): List<DynamicTest> {
        val inputs =
            mapOf(
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
                val converted = valueConverter.convert(type, InputValueWithState.newExternalValue(external))
                if (externalValues.containsKey(coord)) {
                    assertPossiblyNestedIsEqual(externalValues[coord]!!, converted)
                } else {
                    assertNull(converted)
                }
            }
        }
    }
}
