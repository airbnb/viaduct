package viaduct.graphql.schema.validation.rules

import graphql.parser.MultiSourceReader
import graphql.schema.idl.SchemaParser
import java.io.StringReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.SchemaProvenance
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.GraphQLBuiltIns
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes

class PluginScalarReferencesRuleTest {
    private val plugin = "schema/plugin/dev.example.federation/source/"
    private val validator = SchemaValidator(
        listOf(listOf(NoCustomScalarsRule(GraphQLBuiltIns.SCALARS + GraphQLBuiltIns.VIADUCT_SCALARS), PluginScalarReferencesRule())),
    )

    @Test
    fun `same supplier may declare wrapped arguments across files and tenant may apply directives`() {
        val schema = schema(
            "${plugin}scalars.graphqls" to "scalar FieldSet",
            "${plugin}directives.graphqls" to "directive @key(fields: [FieldSet!]) on OBJECT",
            "schema/module/catalog/source/types.graphqls" to "type Product @key(fields: [\"id\"]) { id: ID }",
            "schema/application/source/schemabase/query.graphqls" to "type Query { product: Product }",
            "schema/plugin/other.owner/generated/dev.example.federation/fields.graphqls" to
                "extend type Query { entities(representations: [FieldSet!]!): String }",
        )

        assertTrue(validator.validate(schema).isEmpty())
        assertTrue(SchemaProvenance.isPluginScalar(schema.types.getValue("FieldSet") as ViaductSchema.Scalar))
    }

    @Test
    fun `return and input positions are forbidden even for the defining plugin`() {
        val errors = validator.validate(
            schema(
                "${plugin}types.graphqls" to """
                scalar FieldSet
                type Query { invalid: [FieldSet!] }
                input Input { invalid: FieldSet }
                interface Interface { invalid: FieldSet }
                """.trimIndent(),
            )
        )

        assertEquals(setOf(listOf("Query", "invalid"), listOf("Input", "invalid"), listOf("Interface", "invalid")), errors.map { it.location.path }.toSet())
        assertTrue(errors.all { it.code == ValidationErrorCodes.PLUGIN_SCALAR_REFERENCE_NOT_ALLOWED })
    }

    @Test
    fun `references use the supplying extension rather than the original type owner`() {
        val tenant = "schema/module/catalog/source/extension.graphqls"
        val other = "schema/plugin/dev.example.other/source/extension.graphqls"
        val errors = validator.validate(
            schema(
                "${plugin}types.graphqls" to "scalar FieldSet\ntype Query { own(arg: FieldSet): String }",
                tenant to "extend type Query { tenant(arg: [FieldSet!]): String }",
                other to "extend type Query { other(arg: FieldSet): String }",
                "schema/plugin/dev.example.federation/generated/dev.example.other/directive.graphqls" to
                    "directive @other(arg: FieldSet) on OBJECT",
            )
        )

        assertEquals(3, errors.size)
        assertEquals(setOf(tenant, other, "schema/plugin/dev.example.federation/generated/dev.example.other/directive.graphqls"), errors.map { it.location.sourceLocation?.sourceName }.toSet())
    }

    @Test
    fun `only plugin suppliers may define scalars and standard scalar uses stay unrestricted`() {
        val errors = validator.validate(
            schema(
                "schema/application/source/schemabase/types.graphqls" to "scalar Custom\ntype Query { json: JSON }\nscalar JSON",
                "schema/module/catalog/source/types.graphqls" to "scalar Tenant",
            )
        )

        assertEquals(setOf("Custom", "Tenant"), errors.map { it.location.path.single() }.toSet())
        assertTrue(errors.all { it.code == ValidationErrorCodes.CUSTOM_SCALAR_NOT_ALLOWED })
    }

    private fun schema(vararg sources: Pair<String, String>): ViaductSchema {
        val reader = MultiSourceReader.newMultiSourceReader()
        sources.forEach { (name, sdl) -> reader.reader(StringReader("$sdl\n"), name) }
        return ViaductSchema.fromTypeDefinitionRegistry(SchemaParser().parse(reader.build()))
    }
}
