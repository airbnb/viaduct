@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.schema.diff.SchemaDiff
import graphql.schema.diff.SchemaDiffSet
import graphql.schema.diff.reporting.CapturingReporter
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
import io.kotest.property.Arb
import io.kotest.property.checkAll
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase

class GraphQLSchemasTest : KotestPropertyBase() {
    /**
     * This test makes no assertions but is useful for debugging
     * schema generation.
     * Uncomment the @Test annotation to run, but please don't check in.
     */
    // @Test
    fun `dump 1 schema`(): Unit =
        runBlocking {
            val cfg = Config.default + (DescriptionLength to 0..0)
            Arb.graphQLSchema(cfg).checkAll(1) {
                val sdl = SchemaPrinter().print(it)
                println(sdl)
                markSuccess()
            }
        }

    @Test
    fun `Arb-graphQLSchema can generate an empty-ish schema`(): Unit =
        runBlocking {
            val cfg = Config.default + (SchemaSize to 0)
            Arb.graphQLSchema(cfg).checkAll {
                markSuccess()
            }
        }

    @Test
    fun `schema document can be roundtripped through sdl`(): Unit =
        runBlocking {
            Arb.graphQLSchema().forAll(100) { schema ->
                val sdl = SchemaPrinter().print(schema)
                SchemaParser().parse(sdl)
                true
            }
        }

    /**
     * This test is disabled pending a fix in graphql-java. After the below PR is merged and GJ
     * is upgraded in treehouse, remove the comment describing the issue and update the iteration count,
     * which is set to 1 for debugging.
     *
     *   https://github.com/graphql-java/graphql-java/pull/3599
     */
    // @Test
    fun `validated schema can be roundtripped through sdl`(): Unit =
        runBlocking {
            /**
             * run this test with seed 865193668125215757 (put this value in this class' super call to KotestPropertyBase)
             *
             * This test fails because of GJ validation errors that I think are incorrect.
             * The first error is this:
             *   SchemaProblem: errors=['brz' [@227:3] uses an illegal value for the argument 'cVbIutKbC' on
             *   directive 'Directive_Yx82I6b8'. Argument value is 'NullValue', expected a list value.
             *
             * The hierarchy of types that matter here are:
             *   directive Directive_Yx82I6b8(cVbIutKbC: Input_qgm7)
             *   input Input_qgm7 { o: Input_usQkbUM }
             *   input Input_usQkbUM { ek: [[Enum_r!]] }
             *
             * When validating the schema, GJ encounters a value for [[Enum_r!]] like `[[Foo, Bar], <null>]`, and emits
             * an error.
             * This seems incorrect, as the null value is used in a position where null values are allowed.
             */
            Arb.graphQLSchema().forAll(1) { schema ->
                val sdl = SchemaPrinter().print(schema)
                val schema2 = SchemaGenerator.mkSchema(sdl)
                CapturingReporter().let { reporter ->
                    SchemaDiff().diffSchema(SchemaDiffSet.diffSetFromIntrospection(schema, schema2), reporter)

                    reporter.breakages.isEmpty() && reporter.dangers.isEmpty()
                }
            }
        }
}
