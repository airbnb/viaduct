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
    fun `materializing the full validScopes set as a scope set passes when root types tag @scope`() {
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

        // A caller can request the universe as an explicit scope set (equivalent to what a
        // scopedSchema declaration listing every universe scope would produce). Must introspect
        // cleanly when Query is `@scope(to: ["*"])` and therefore present in every projection.
        val failures = validator.validate(setOf("internal", "public"))
        failures shouldBe emptyList()
    }

    @Test
    fun `validateBase materializes the base schema and passes on a clean schema`() {
        val schema = parseSchema(
            """
            $boilerplate
            type Query @scope(to: ["public"]) {
                hello: String
            }
            """.trimIndent()
        )
        val validator = ScopedSchemaValidator(schema, sortedSetOf("public"))

        val messages = validator.validateBase()

        messages shouldBe emptyList()
        validator.basesValidated shouldBe 1
        // BASE is not a scope set — it must not appear in validatedScopeSets.
        validator.validatedScopeSets shouldBe emptyList<Set<String>>()
    }

    @Test
    fun `validate captures SchemaScopeValidationError instead of propagating (Throwable-not-Exception)`() {
        // Reproduces the DirectiveRetainedTypeScopeError fixture: a `@scope`-restricted type
        // referenced by a directive definition. `applyScopes(setOf("other-scope"))` throws
        // DirectiveRetainedTypeScopeError, which extends SchemaScopeValidationError → Throwable
        // (not Exception). The validator must catch it and return a Failure rather than letting
        // it escape and abort aggregation across other scope sets.
        val schema = parseSchema(
            """
            $boilerplate
            enum SimpleEnum @scope(to: ["test-scope"]) { Foo }
            directive @directiveWithEnum(x: SimpleEnum) on FIELD_DEFINITION
            type Query @scope(to: ["*"]) {
                a: Int @directiveWithEnum(x: Foo)
            }
            """.trimIndent()
        )
        val validator = ScopedSchemaValidator(schema, sortedSetOf("test-scope", "other-scope"))

        // Should NOT throw — must be captured as a Failure.
        val failures = shouldNotThrowAny { validator.validate(setOf("other-scope")) }

        failures shouldNotBe emptyList<ScopedSchemaValidator.Failure>()
        failures.forEach { it.scopeSet shouldBe setOf("other-scope") }
        val combined = failures.joinToString("\n") { it.message }
        combined shouldContain "materialization failed"
    }

    @Test
    fun `sequential validate calls after a SchemaScopeValidationError all run and record`() {
        // Regression pin for the aggregation contract: when validate(A) hits a
        // SchemaScopeValidationError, it must return a Failure rather than propagate — so the
        // caller's iteration reaches validate(B) too. Before catching the Throwable-based
        // SchemaScopeValidationError, the error escaped and aborted the entire loop at set A;
        // set B was silently skipped and any failures it had never reached the aggregated report.
        val schema = parseSchema(
            """
            $boilerplate
            enum SimpleEnum @scope(to: ["test-scope"]) { Foo }
            directive @directiveWithEnum(x: SimpleEnum) on FIELD_DEFINITION
            type Query @scope(to: ["*"]) {
                a: Int @directiveWithEnum(x: Foo)
            }
            """.trimIndent()
        )
        val validator = ScopedSchemaValidator(schema, sortedSetOf("test-scope", "other-scope"))

        // Both scope sets trigger DirectiveRetainedTypeScopeError under this schema. The point
        // is that both calls RUN and both are RECORDED — the second call proves the first didn't
        // propagate its Throwable.
        val failuresA = shouldNotThrowAny { validator.validate(setOf("other-scope")) }
        val failuresB = shouldNotThrowAny { validator.validate(setOf("test-scope")) }

        failuresA shouldNotBe emptyList<ScopedSchemaValidator.Failure>()
        failuresB shouldNotBe emptyList<ScopedSchemaValidator.Failure>()
        failuresA.forEach { it.scopeSet shouldBe setOf("other-scope") }
        failuresB.forEach { it.scopeSet shouldBe setOf("test-scope") }
        validator.validatedScopeSets shouldContainExactlyInAnyOrder listOf(
            setOf("other-scope"),
            setOf("test-scope")
        )
    }
}
