package viaduct.graphql.schema.graphqljava

import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.checkViaductSchemaInvariants
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.test.SchemaDiff
import viaduct.invariants.FailureCollector

/**
 * Runs a fairly complete test of GJSchema using the 5k randomly generated schema.
 * This is an OSS-friendly version of FullAirbnbSchemaTest that doesn't require
 * the consolidated central Airbnb schema.
 *
 * The test strategy here is to have an input schema that hits various
 * corner-cases of graphql-java input syntax, and then run invariant-tests
 * and agreement-tests (agreement between GJSchema and GJSchemaRaw) against
 * the generated schemas.
 */
class Full5kSchemaTest {
    companion object {
        lateinit var gjSchema: GraphQLSchema
        lateinit var schema: ViaductSchema
        lateinit var schemaraw: ViaductSchema

        @BeforeAll
        @JvmStatic
        fun loadSchema() {
            val classLoader = Full5kSchemaTest::class.java.classLoader
            val schemaSrc = classLoader.getResourceAsStream("arb-schema-5k/arb-schema-5k.graphqls")
                ?: throw IllegalStateException("Could not load arb-schema-5k.graphqls")
            val reg = SchemaParser().parse(schemaSrc)
            gjSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(reg)
            schema = ViaductSchema.fromGraphQLSchema(gjSchema)

            // Add builtin directives to GJSchemaRaw only if not already present in the schema.
            // The arb-schema-5k already has some directives defined.
            val builtinDirectives = listOf("include", "skip", "defer", "experimental_disableErrorPropagation")
            val missingDirectives = builtinDirectives.filter { !reg.getDirectiveDefinition(it).isPresent }
            if (missingDirectives.isNotEmpty()) {
                val directivesDef = buildString {
                    if ("include" in missingDirectives) appendLine("directive @include(if: Boolean!) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT")
                    if ("skip" in missingDirectives) appendLine("directive @skip(if: Boolean!) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT")
                    if ("defer" in missingDirectives) appendLine("directive @defer(if: Boolean! = true, label: String) on FRAGMENT_SPREAD | INLINE_FRAGMENT")
                    if ("experimental_disableErrorPropagation" in missingDirectives) appendLine("directive @experimental_disableErrorPropagation on QUERY | MUTATION | SUBSCRIPTION")
                }
                if (directivesDef.isNotBlank()) {
                    reg.merge(SchemaParser().parse(directivesDef))
                }
            }
            schemaraw = ViaductSchema.fromTypeDefinitionRegistry(reg)
            Assertions.assertNotNull(schema)
        }
    }

    @Test
    fun `run GJSchemaCheck on 5k schema`() {
        GJSchemaCheck(schema as SchemaWithData, gjSchema).assertEmpty("\n")
    }

    @Test
    fun `run schema invariant checks on GJSchemaRaw`() {
        FailureCollector().also { check ->
            checkViaductSchemaInvariants(schemaraw, check)
        }.assertEmpty("\n")
    }

    @Test
    fun `sanity check of schema differencer`() {
        SchemaDiff(schema, schema, includeIntrospectiveTypes = true).diff().assertEmpty("\n")
    }

    @Test
    fun `compare GJSchemaRaw to GJSchema`() {
        SchemaDiff(schema, schemaraw).diff().assertEmpty("\n")
    }

    @Test
    fun `noop filter preserves 5k schema identity`() {
        val classLoader = Full5kSchemaTest::class.java.classLoader
        val schemaSrc = classLoader.getResourceAsStream("arb-schema-5k/arb-schema-5k.graphqls")
            ?: throw IllegalStateException("Could not load arb-schema-5k.graphqls")
        val reg = SchemaParser().parse(schemaSrc)
        val arbSchema = ViaductSchema.fromTypeDefinitionRegistry(reg)

        val noopFilteredArbSchema = arbSchema.filter(NoopSchemaFilter())
        SchemaDiff(arbSchema, noopFilteredArbSchema).diff().assertEmpty("\n")
    }
}
