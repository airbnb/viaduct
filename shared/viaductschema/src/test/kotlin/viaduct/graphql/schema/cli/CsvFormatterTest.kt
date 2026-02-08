package viaduct.graphql.schema.cli

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.test.mkSchema

class CsvFormatterTest {
    // ========== escapeForCsv() tests ==========

    @Test
    fun `escapeForCsv returns unchanged string when no quotes`() {
        assertEquals("hello world", escapeForCsv("hello world"))
    }

    @Test
    fun `escapeForCsv doubles single quote`() {
        assertEquals("say \"\"hello\"\"", escapeForCsv("say \"hello\""))
    }

    @Test
    fun `escapeForCsv doubles multiple quotes`() {
        assertEquals("a\"\"b\"\"c\"\"d", escapeForCsv("a\"b\"c\"d"))
    }

    @Test
    fun `escapeForCsv handles empty string`() {
        assertEquals("", escapeForCsv(""))
    }

    @Test
    fun `escapeForCsv handles string of just quotes`() {
        assertEquals("\"\"\"\"", escapeForCsv("\"\""))
    }

    // ========== formatDirectiveForCsv() tests ==========

    @Test
    fun `formatDirectiveForCsv formats simple directive`() {
        val schema = mkSchema(
            """
            directive @deprecated on FIELD_DEFINITION
            type Foo @deprecated {
                bar: String
            }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"]!!
        val deprecatedDir = fooDef.appliedDirectives.find { it.name == "deprecated" }!!

        val result = formatDirectiveForCsv(deprecatedDir)

        assertEquals("deprecated,\"@deprecated\"", result)
    }

    @Test
    fun `formatDirectiveForCsv formats directive with string argument`() {
        val schema = mkSchema(
            """
            directive @doc(text: String!) on OBJECT
            type Foo @doc(text: "hello world") {
                bar: String
            }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"]!!
        val docDir = fooDef.appliedDirectives.find { it.name == "doc" }!!

        val result = formatDirectiveForCsv(docDir)

        // The directive toString() includes quotes around string args
        // Those quotes get doubled by escapeForCsv
        assertTrue(result.startsWith("doc,\"@doc("), "Should start with directive name and opening")
        assertTrue(result.contains("\"\"hello world\"\""), "Should have escaped quotes around string value")
    }

    @Test
    fun `formatDirectiveForCsv formats directive with list argument`() {
        val schema = mkSchema(
            """
            directive @owners(teams: [String!]!) on OBJECT
            type Foo @owners(teams: ["team-a", "team-b"]) {
                bar: String
            }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"]!!
        val ownersDir = fooDef.appliedDirectives.find { it.name == "owners" }!!

        val result = formatDirectiveForCsv(ownersDir)

        assertEquals("owners", result.substringBefore(","))
        assertTrue(result.contains("teams:"), "Should contain argument name")
    }

    @Test
    fun `formatDirectiveForCsv formats directive with boolean argument`() {
        val schema = mkSchema(
            """
            directive @privacy(delegateToParent: Boolean!) on FIELD_DEFINITION
            type Foo {
                bar: String @privacy(delegateToParent: true)
            }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"] as ViaductSchema.Record
        val barField = fooDef.fields.find { it.name == "bar" }!!
        val privacyDir = barField.appliedDirectives.find { it.name == "privacy" }!!

        val result = formatDirectiveForCsv(privacyDir)

        assertTrue(result.startsWith("privacy,\"@privacy("))
        assertTrue(result.contains("delegateToParent:true") || result.contains("delegateToParent: true"))
    }

    // ========== formatDirectivesForCsv() tests ==========

    @Test
    fun `formatDirectivesForCsv returns NONE for empty list`() {
        val result = formatDirectivesForCsv(emptyList())

        assertEquals(1, result.size)
        assertEquals("NONE,", result[0])
    }

    @Test
    fun `formatDirectivesForCsv returns one entry per directive`() {
        val schema = mkSchema(
            """
            directive @a on OBJECT
            directive @b on OBJECT
            directive @c on OBJECT
            type Foo @a @b @c {
                bar: String
            }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"]!!
        val result = formatDirectivesForCsv(fooDef.appliedDirectives.toList())

        assertEquals(3, result.size)
        assertTrue(result.any { it.startsWith("a,") }, "Should have entry for @a")
        assertTrue(result.any { it.startsWith("b,") }, "Should have entry for @b")
        assertTrue(result.any { it.startsWith("c,") }, "Should have entry for @c")
    }

    @Test
    fun `formatDirectivesForCsv handles repeatable directive`() {
        val schema = mkSchema(
            """
            directive @tag(name: String!) repeatable on OBJECT
            type Foo @tag(name: "one") @tag(name: "two") {
                bar: String
            }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"]!!
        val result = formatDirectivesForCsv(fooDef.appliedDirectives.toList())

        assertEquals(2, result.size)
        assertTrue(result.all { it.startsWith("tag,") }, "Both entries should be for @tag")
    }

    @Test
    fun `formatDirectivesForCsv preserves directive order`() {
        val schema = mkSchema(
            """
            directive @first on OBJECT
            directive @second on OBJECT
            directive @third on OBJECT
            type Foo @first @second @third {
                bar: String
            }
            """.trimIndent()
        )

        val fooDef = schema.types["Foo"]!!
        val result = formatDirectivesForCsv(fooDef.appliedDirectives.toList())

        // Verify order matches the order of applied directives on the type
        val directiveNames = result.map { it.substringBefore(",") }
        val expectedOrder = fooDef.appliedDirectives.map { it.name }
        assertEquals(expectedOrder, directiveNames)
    }
}
