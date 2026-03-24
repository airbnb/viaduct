package viaduct.engine.api.fragment

import graphql.language.AstPrinter
import graphql.language.Field
import graphql.language.VariableReference
import graphql.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FragmentTest {
    @Test
    fun testCorrectlyParsesFragmentWithoutArguments() {
        val fragment =
            fragment(
                """
                fragment Foo on SomeType {
                  field1
                  field2
                }
                """.trimIndent()
            )
        assertEquals("Foo", fragment.definition.name)
        assertEquals("SomeType", fragment.definition.typeCondition.name)
    }

    @Test
    fun testCorrectlyParsesFragmentWithArguments() {
        val args =
            FragmentVariables.fromMap(
                mapOf<String, Any?>(
                    "arg1" to "someValue",
                    "arg2" to null,
                    "arg3" to 10
                )
            )

        val arg1: FragmentVariable<String?> by args
        val arg2: FragmentVariable<String?> by args
        val arg3: FragmentVariable<Int?> by args

        val fragment =
            fragment(
                """
                fragment Foo on SomeType {
                  field1(arg1: $arg1, arg2: $arg2, arg3: $arg3)
                  field2
                }
                """.trimIndent(),
                args
            )
        assertEquals("Foo", fragment.definition.name)
        assertEquals("SomeType", fragment.definition.typeCondition.name)

        val field1 = fragment.definition.selectionSet.selections[0] as Field
        assertEquals("arg1", field1.arguments[0].name)
        assertEquals("arg1", (field1.arguments[0].value as VariableReference).name)
    }

    @Test
    fun testCorrectlyParsesFragmentWithArgumentsFromConstructor() {
        val args =
            FragmentVariables.fromMap(
                mapOf<String, Any?>(
                    "arg1" to "someValue",
                    "arg2" to null,
                    "arg3" to 10
                )
            )

        val arg1: FragmentVariable<String?> by args
        val arg2: FragmentVariable<String?> by args
        val arg3: FragmentVariable<Int?> by args

        val fragment =
            Fragment(
                """
                fragment Foo on SomeType {
                  field1(arg1: $arg1, arg2: $arg2, arg3: $arg3)
                  field2
                }
                """.trimIndent(),
                args
            )
        assertEquals("Foo", fragment.definition.name)
        assertEquals("SomeType", fragment.definition.typeCondition.name)

        val field1 = fragment.definition.selectionSet.selections[0] as Field
        assertEquals("arg1", field1.arguments[0].name)
        assertEquals("arg1", (field1.arguments[0].value as VariableReference).name)
    }

    @Test
    fun testCorrectlyParsesFragmentWithArgumentsFromConstructorWithVarMap() {
        val argMap =
            mapOf<String, Any?>(
                "arg1" to "someValue",
                "arg2" to null,
                "arg3" to 10
            )
        val args = FragmentVariables.fromMap(argMap)

        val arg1: FragmentVariable<String?> by args
        val arg2: FragmentVariable<String?> by args
        val arg3: FragmentVariable<Int?> by args

        val fragment =
            Fragment(
                """
                fragment Foo on SomeType {
                  field1(arg1: $arg1, arg2: $arg2, arg3: $arg3)
                  field2
                }
                """.trimIndent(),
                argMap
            )
        assertEquals("Foo", fragment.definition.name)
        assertEquals("SomeType", fragment.definition.typeCondition.name)

        val field1 = fragment.definition.selectionSet.selections[0] as Field
        assertEquals("arg1", field1.arguments[0].name)
        assertEquals("arg1", (field1.arguments[0].value as VariableReference).name)
    }

    @Test
    fun testThrowsErrorsWhenReferencingNonExistentVariable() {
        val var1 = FragmentVariable("var1", "some value")
        val exception =
            assertThrows(ViaductFragmentParsingError::class.java) {
                fragment(
                    """
                    fragment Foo on SomeType {
                      field1(arg: $var1, arg2: ${"$"}var2)
                    }
                    """.trimIndent(),
                    FragmentVariables.fromVariables(var1)
                ).definition
            }
        assertEquals(
            """
            Fragment 'Foo' has unbound variables:
            * var2
            Ensure they are passed in the `vars` argument when creating this fragment.
            """.trimIndent(),
            exception.cause?.message
        )
    }

    @Test
    fun testCapturesMultipleErrorsDuringFragmentParsing() {
        val var1 = FragmentVariable("var1", "some value")
        val exception =
            assertThrows(ViaductFragmentParsingError::class.java) {
                fragment(
                    """
                    fragment Foo on SomeType {
                      field1(arg: $var1, arg2: ${"$"}var2)
                      field2(arg: 10)
                      ...on SomeType {
                        foo: field1(arg3: ${"$"}var3)
                      }
                    }
                    """.trimIndent(),
                    FragmentVariables.fromVariables(var1)
                ).definition
            }
        @Suppress("MaxLineLength")
        assertEquals(
            """
            Fragment 'Foo' has unbound variables:
            * var2
            * var3
            Ensure they are passed in the `vars` argument when creating this fragment.
            """.trimIndent(),
            exception.cause?.message
        )
    }

    @Test
    fun testFieldIteratorHasCorrectNumberOfFields() {
        val fragText =
            Fragment(
                """
                fragment Foo on SomeType {
                  field1
                  field2
                }
                """.trimIndent()
            )

        val fieldList = fragText.iterator().asSequence().toList()
        assertEquals(3, fieldList.size)
    }

    @Test
    fun `testFragmentSourceEmpty`() {
        val dd = FragmentSource.Companion.Empty
        assertEquals("", dd.documentString())
        assertTrue(dd.document().definitions.isEmpty())
    }

    @Test
    fun `testFragmentSourceFromString`() {
        val str =
            """
            # some comment
            fragment Foo on SomeType {
                field1
                field2
            }
            """.trimIndent()
        val dd = FragmentSource.create(str)
        assertEquals(str, dd.documentString())

        assertEquals(
            "fragment Foo on SomeType {field1 field2}",
            AstPrinter.printAstCompact(dd.document())
        )
    }

    @Test
    fun `testFragmentSourceFromDocument`() {
        val str =
            """
            fragment Foo on SomeType {
                field1
                field2
            }
            """.trimIndent()
        val doc = Parser.parse(str)
        val dd = FragmentSource.create(doc)
        assertEquals(doc, dd.document())

        assertEquals(
            "fragment Foo on SomeType {field1 field2}",
            AstPrinter.printAstCompact(dd.document())
        )
        assertEquals(
            AstPrinter.printAstCompact(doc),
            dd.documentString()
        )
    }
}
