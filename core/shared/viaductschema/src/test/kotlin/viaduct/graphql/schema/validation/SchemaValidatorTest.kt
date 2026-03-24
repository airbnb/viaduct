package viaduct.graphql.schema.validation

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

class SchemaValidatorTest {
    @Test
    fun `empty rules produces no errors`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry("type Query { hello: String }")
        val validator = SchemaValidator(phases = emptyList())

        val errors = validator.validate(schema)

        errors.shouldBeEmpty()
    }

    @Test
    fun `multiple errors are collected`() {
        val multiErrorRule = object : ValidationRule("multi-error") {
            override fun visitSchema(ctx: ValidationContext) {
                ctx.reportError("ERROR_1", "First error", SchemaLocation.ofType("Query"))
                ctx.reportError("ERROR_2", "Second error", SchemaLocation.ofType("Query"))
            }
        }

        val schema = ViaductSchema.fromTypeDefinitionRegistry("type Query { hello: String }")
        val validator = SchemaValidator(listOf(listOf(multiErrorRule)))

        val errors = validator.validate(schema)

        errors shouldHaveSize 2
        errors.map { it.code } shouldContainExactly listOf("ERROR_1", "ERROR_2")
    }

    @Test
    fun `phases execute in order`() {
        val executionOrder = mutableListOf<String>()

        val phase1Rule = object : ValidationRule("phase1") {
            override fun visitSchema(ctx: ValidationContext) {
                executionOrder.add("phase1")
            }
        }
        val phase2Rule = object : ValidationRule("phase2") {
            override fun visitSchema(ctx: ValidationContext) {
                executionOrder.add("phase2")
            }
        }

        val schema = ViaductSchema.fromTypeDefinitionRegistry("type Query { hello: String }")
        val validator = SchemaValidator(listOf(listOf(phase1Rule), listOf(phase2Rule)))

        validator.validate(schema)

        executionOrder shouldContainExactly listOf("phase1", "phase2")
    }

    @Test
    fun `SchemaLocation toString formats correctly`() {
        SchemaLocation.ofType("Query").toString() shouldBe "Query"
        SchemaLocation.ofField("Query", "hello").toString() shouldBe "Query.hello"
        SchemaLocation.ofDirective("deprecated").toString() shouldBe "@deprecated"
    }

    @Test
    fun `SchemaLocation toString includes source location when available`() {
        val sourceLocation = ViaductSchema.SourceLocation("schema.graphql")
        val location = SchemaLocation.ofType("Query").withSourceLocation(sourceLocation)

        location.toString() shouldContain "schema.graphql:"
        location.toString() shouldEndWith "Query"
    }

    @Test
    fun `SchemaLocation toString omits source location when null`() {
        val location = SchemaLocation.ofField("Query", "hello")

        location.toString() shouldBe "Query.hello"
    }

    @Test
    fun `validate with pre-constructed context collects errors`() {
        val rule = object : ValidationRule("test") {
            override fun visitSchema(ctx: ValidationContext) {
                ctx.reportError("TEST_ERROR", "Test error", SchemaLocation.ofType("Query"))
            }
        }
        val schema = ViaductSchema.fromTypeDefinitionRegistry("type Query { hello: String }")
        val ctx = ValidationContext(schema)
        val validator = SchemaValidator(listOf(listOf(rule)))

        val errors = validator.validate(ctx)

        errors shouldHaveSize 1
        errors.first().code shouldBe "TEST_ERROR"
    }

    @Test
    fun `validate with context accumulates errors from multiple validators`() {
        val rule1 = object : ValidationRule("validator1") {
            override fun visitSchema(ctx: ValidationContext) {
                ctx.reportError("ERROR_FROM_V1", "Error from validator 1", SchemaLocation.ofType("Query"))
            }
        }
        val rule2 = object : ValidationRule("validator2") {
            override fun visitSchema(ctx: ValidationContext) {
                ctx.reportError("ERROR_FROM_V2", "Error from validator 2", SchemaLocation.ofType("Query"))
            }
        }
        val schema = ViaductSchema.fromTypeDefinitionRegistry("type Query { hello: String }")
        val ctx = ValidationContext(schema)
        val validator1 = SchemaValidator(listOf(listOf(rule1)))
        val validator2 = SchemaValidator(listOf(listOf(rule2)))

        validator1.validate(ctx)
        val allErrors = validator2.validate(ctx)

        allErrors shouldHaveSize 2
        allErrors.map { it.code } shouldContainExactly listOf("ERROR_FROM_V1", "ERROR_FROM_V2")
    }

    @Test
    fun `validate with context preserves pre-existing errors`() {
        val rule = object : ValidationRule("test") {
            override fun visitSchema(ctx: ValidationContext) {
                ctx.reportError("NEW_ERROR", "New error from rule", SchemaLocation.ofType("Query"))
            }
        }
        val schema = ViaductSchema.fromTypeDefinitionRegistry("type Query { hello: String }")
        val ctx = ValidationContext(schema)
        ctx.reportError("PRE_EXISTING", "Pre-existing error", SchemaLocation.ofType("Query"))
        val validator = SchemaValidator(listOf(listOf(rule)))

        val errors = validator.validate(ctx)

        errors shouldHaveSize 2
        errors.map { it.code } shouldContainExactly listOf("PRE_EXISTING", "NEW_ERROR")
    }
}
