package viaduct.graphql.scopes

import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.util.SortedSet
import org.junit.jupiter.api.Test
import viaduct.graphql.utils.DefaultSchemaFactory

class ScopedSchemaValidatorTest {
    private val boilerplate = """
        directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION | SCALAR | FIELD_DEFINITION
    """

    private fun parseSchema(sdl: String): GraphQLSchema {
        val registry = SchemaParser().parse(sdl).apply {
            DefaultSchemaFactory.addDefaults(this, allowExisting = true)
        }
        return UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
    }

    private fun sortedSetOf(vararg s: String): SortedSet<String> = s.toSortedSet()

    @Test
    fun `passes when materializing a fully-populated declared scope set`() {
        val schema = parseSchema(
            """
            $boilerplate
            type Query @scope(to: ["public"]) {
                hello: String
            }
            """.trimIndent()
        )

        val validator = ScopedSchemaValidator(schema, sortedSetOf("public"))
        val failures = validator.validate(setOf("public"))

        failures shouldBe emptyList()
        validator.validatedScopeSets shouldBe listOf(setOf("public"))
    }

    @Test
    fun `passes when materializing multiple distinct declared scope sets`() {
        val schema = parseSchema(
            """
            $boilerplate
            type Query @scope(to: ["*"]) {
                hi: String @scope(to: ["public"])
                secret: String @scope(to: ["internal"])
            }
            """.trimIndent()
        )
        val validator = ScopedSchemaValidator(schema, sortedSetOf("internal", "public"))

        validator.validate(setOf("public")) shouldBe emptyList()
        validator.validate(setOf("internal", "public")) shouldBe emptyList()

        validator.validatedScopeSets shouldContainExactlyInAnyOrder listOf(
            setOf("public"),
            setOf("internal", "public")
        )
    }

    @Test
    fun `each validate call records exactly one entry in validatedScopeSets`() {
        val schema = parseSchema(
            """
            $boilerplate
            type Query @scope(to: ["*"]) {
                hello: String
            }
            """.trimIndent()
        )
        val validator = ScopedSchemaValidator(schema, sortedSetOf("public"))

        validator.validate(setOf("public"))
        validator.validate(setOf("public"))
        validator.validate(setOf("public"))

        validator.validatedScopeSets shouldHaveSize 3
        validator.validatedScopeSets.forEach { it shouldBe setOf("public") }
    }

    @Test
    fun `fails when scope set filters away the Query root type`() {
        val schema = parseSchema(
            """
            $boilerplate
            type Query @scope(to: ["public"]) {
                hello: String
            }
            """.trimIndent()
        )
        // Universe contains "phantom" but no SDL element is tagged with it.
        // Materializing {phantom} leaves the schema with no Query type.
        val validator = ScopedSchemaValidator(schema, sortedSetOf("phantom", "public"))
        val failures = validator.validate(setOf("phantom"))

        failures shouldNotBe emptyList<ScopedSchemaValidator.Failure>()
        failures.forEach { it.scopeSet shouldBe setOf("phantom") }
        val combinedMessage = failures.joinToString("\n") { it.message }
        combinedMessage.lowercase() shouldContain "query"
    }

    @Test
    fun `failure messages identify the scope set, not any external alias`() {
        val schema = parseSchema(
            """
            $boilerplate
            type Query @scope(to: ["public"]) {
                hello: String
            }
            """.trimIndent()
        )
        val validator = ScopedSchemaValidator(schema, sortedSetOf("phantom", "public"))
        val failures = validator.validate(setOf("phantom"))

        failures shouldNotBe emptyList<ScopedSchemaValidator.Failure>()
        // Alias names never enter the helper — Failure.scopeSet is the sole source of truth for
        // downstream diagnostics. This test pins that contract so a future refactor can't leak an
        // alias string into the failure payload.
        failures.forEach { it.scopeSet shouldBe setOf("phantom") }
    }

    @Test
    fun `no-op when validator is never called — validatedScopeSets stays empty`() {
        val schema = parseSchema(
            """
            $boilerplate
            type Query @scope(to: ["*"]) {
                hello: String
            }
            """.trimIndent()
        )
        val validator = ScopedSchemaValidator(schema, sortedSetOf("public"))
        validator.validatedScopeSets shouldBe emptyList<Set<String>>()
    }

    @Test
    fun `handles empty scope set input without throwing`() {
        val schema = parseSchema(
            """
            $boilerplate
            type Query @scope(to: ["*"]) {
                hello: String
            }
            """.trimIndent()
        )
        val validator = ScopedSchemaValidator(schema, sortedSetOf("public"))

        // The caller (task) is responsible for translating empty-set aliases to the universe set;
        // the helper itself must handle whatever scope set it receives without crashing so the
        // caller's iteration doesn't have to guard.
        shouldNotThrowAny { validator.validate(emptySet()) }
        validator.validatedScopeSets shouldContain emptySet()
    }

    @Test
    fun `materializing the full validScopes set (BASE-equivalent) passes when root types tag @scope`() {
        val schema = parseSchema(
            """
            $boilerplate
            type Query @scope(to: ["*"]) {
                a: String @scope(to: ["public"])
                b: String @scope(to: ["internal"])
            }
            """.trimIndent()
        )
        val validator = ScopedSchemaValidator(schema, sortedSetOf("internal", "public"))

        // The always-materialized "universe" set (D3) — full validScopes — must produce a valid
        // introspectable schema. `@scope(to: ["*"])` on Query means Query is present in every
        // filtered projection, including the full-universe one.
        val failures = validator.validate(setOf("internal", "public"))
        failures shouldBe emptyList()
    }
}
