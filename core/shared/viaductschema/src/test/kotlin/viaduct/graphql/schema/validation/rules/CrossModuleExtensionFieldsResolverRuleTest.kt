package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes

class CrossModuleExtensionFieldsResolverRuleTest {
    private fun rule() = CrossModuleExtensionFieldsResolverRule(modulePathPrefix = "partition/")

    private fun validate(vararg resourcePaths: String) =
        SchemaValidator(listOf(listOf(rule()))).validate(
            ViaductSchema.fromTypeDefinitionRegistry(
                resourcePaths.map { javaClass.getResource(it)!! }
            )
        )

    @Test
    fun `valid - cross-module extension fields all have @resolver`() {
        val errors = validate(
            "/validation/application/query.graphql",
            "/validation/partition/modulea/graphql/object_types.graphql",
            "/validation/partition/moduleb/graphql/object_extensions_with_resolvers.graphql",
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - same-module extension fields without @resolver are allowed`() {
        val errors = validate(
            "/validation/application/query.graphql",
            "/validation/partition/modulea/graphql/object_types.graphql",
            "/validation/partition/modulea/graphql/object_same_module_extension.graphql",
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - application-level extension fields without @resolver are allowed`() {
        val sdl = """
            directive @resolver(isSelective: Boolean! = false, isBatching: Boolean! = false) on OBJECT | FIELD_DEFINITION
            type Query { hello: String }
            type MyObject { id: String }
            extend type MyObject { description: String }
        """.trimIndent()
        val errors = SchemaValidator(listOf(listOf(rule())))
            .validate(ViaductSchema.fromTypeDefinitionRegistry(sdl))
        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - cross-module extension field missing @resolver`() {
        val errors = validate(
            "/validation/application/query.graphql",
            "/validation/partition/modulea/graphql/object_types.graphql",
            "/validation/partition/moduleb/graphql/object_extensions_missing_resolvers.graphql",
        )
        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.CROSS_MODULE_EXTENSION_FIELD_MISSING_RESOLVER
        errors[0].message shouldContain "MyObject.score"
        errors[0].message shouldContain "moduleb"
        errors[0].message shouldContain "@resolver"
    }

    @Test
    fun `invalid - multiple fields in cross-module extension missing @resolver each produce an error`() {
        val errors = validate(
            "/validation/application/query.graphql",
            "/validation/partition/modulea/graphql/object_types.graphql",
            "/validation/partition/modulec/graphql/object_extensions_all_missing_resolvers.graphql",
        )
        errors shouldHaveSize 2
        errors.all { it.code == ValidationErrorCodes.CROSS_MODULE_EXTENSION_FIELD_MISSING_RESOLVER } shouldBe true
        errors.map { it.message }.any { it.contains("score") } shouldBe true
        errors.map { it.message }.any { it.contains("tag") } shouldBe true
    }
}
