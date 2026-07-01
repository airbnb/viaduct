package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes

class NoCrossModuleInputExtensionsRuleTest {
    private fun rule() = NoCrossModuleInputExtensionsRule(modulePathPrefix = "partition/")

    private fun validate(vararg resourcePaths: String) =
        SchemaValidator(listOf(listOf(rule()))).validate(
            ViaductSchema.fromTypeDefinitionRegistry(
                resourcePaths.map { javaClass.getResource(it)!! }
            )
        )

    @Test
    fun `should pass when enum and input are extended within the same module`() {
        val errors = validate(
            "/validation/application/query.graphql",
            "/validation/partition/modulea/graphql/types.graphql",
            "/validation/partition/modulea/graphql/extensions.graphql",
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `should pass when object type is extended across modules`() {
        val sdl = """
            type Query { hello: String }
            extend type Query { world: String }
        """.trimIndent()
        val errors = SchemaValidator(listOf(listOf(rule())))
            .validate(ViaductSchema.fromTypeDefinitionRegistry(sdl))

        errors.shouldBeEmpty()
    }

    @Test
    fun `should pass when enum is defined and extended at application level (no partition path)`() {
        val sdl = """
            type Query { hello: String }
            enum Status { ACTIVE }
            extend enum Status { INACTIVE }
        """.trimIndent()
        val errors = SchemaValidator(listOf(listOf(rule())))
            .validate(ViaductSchema.fromTypeDefinitionRegistry(sdl))

        errors.shouldBeEmpty()
    }

    @Test
    fun `should fail when enum is extended from a different module`() {
        val errors = validate(
            "/validation/application/query.graphql",
            "/validation/partition/modulea/graphql/types.graphql",
            "/validation/partition/moduleb/graphql/extensions.graphql",
        )

        val enumErrors = errors.filter { it.code == ValidationErrorCodes.CROSS_MODULE_INPUT_EXTENSION && "enum" in it.message }
        enumErrors shouldHaveSize 1
        enumErrors[0].message shouldContain "MyStatus"
        enumErrors[0].message shouldContain "modulea"
        enumErrors[0].message shouldContain "moduleb"
    }

    @Test
    fun `should pass when application level schema extends a module-defined type`() {
        val errors = validate(
            "/validation/application/query.graphql",
            "/validation/partition/modulea/graphql/types.graphql",
            "/validation/application/extends_module_types.graphql",
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `should fail when input is extended from a different module`() {
        val errors = validate(
            "/validation/application/query.graphql",
            "/validation/partition/modulea/graphql/types.graphql",
            "/validation/partition/moduleb/graphql/extensions.graphql",
        )

        val inputErrors = errors.filter { it.code == ValidationErrorCodes.CROSS_MODULE_INPUT_EXTENSION && "input" in it.message }
        inputErrors shouldHaveSize 1
        inputErrors[0].message shouldContain "MyInput"
        inputErrors[0].message shouldContain "modulea"
        inputErrors[0].message shouldContain "moduleb"
    }

    @Test
    fun `should fail when module extends schemabase-defined enum and input types`() {
        val errors = validate(
            "/validation/application/query.graphql",
            "/validation/application/schemabase_types.graphql",
            "/validation/partition/modulea/graphql/extends_schemabase_types.graphql",
        )

        val appbaseErrors = errors.filter { it.code == ValidationErrorCodes.APPBASE_INPUT_EXTENSION }
        appbaseErrors shouldHaveSize 2

        val enumErrors = appbaseErrors.filter { "enum" in it.message }
        enumErrors shouldHaveSize 1
        enumErrors[0].message shouldContain "AppStatus"
        enumErrors[0].message shouldContain "modulea"
        enumErrors[0].message shouldContain "schemabase"

        val inputErrors = appbaseErrors.filter { "input" in it.message }
        inputErrors shouldHaveSize 1
        inputErrors[0].message shouldContain "AppInput"
        inputErrors[0].message shouldContain "modulea"
        inputErrors[0].message shouldContain "schemabase"
    }
}
