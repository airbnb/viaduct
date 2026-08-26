@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.ParseAndValidate
import graphql.language.Field
import graphql.parser.Parser
import io.kotest.property.Arb
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.CompoundingWeight
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.asSequence
import viaduct.graphql.utils.allChildrenOfType

class ViaductExecutionInputsTest : KotestPropertyBase() {
    @Test
    fun `generates valid viaduct execution inputs`(): Unit =
        runBlocking {
            val schema = "extend type Query { x(a:Int!):Int, y:Int }".asViaductSchema

            Arb.viaductExecutionInput(schema).forAll { input ->
                !ParseAndValidate.parseAndValidate(
                    schema.schema,
                    graphql.ExecutionInput
                        .newExecutionInput()
                        .query(input.operationText)
                        .apply { input.operationName?.let(::operationName) }
                        .variables(input.variables)
                        .build()
                ).isFailure
            }
        }

    @Test
    fun `includes operation name`(): Unit =
        runBlocking {
            val seq = Arb.viaductExecutionInput(
                "extend type Query { x:Int }".asViaductSchema,
                Config.default + (OperationCount to 2.asIntRange())
            ).asSequence(randomSource)

            seq.take(1000)
                .any { inp -> inp.operationName != null }
        }

    @Test
    fun `includes variables`(): Unit =
        runBlocking {
            val seq = Arb.viaductExecutionInput(
                "extend type Query { x(a:Int!):Int }".asViaductSchema,
                Config.default + (VariableWeight to 1.0)
            ).asSequence(randomSource)

            seq.take(1000)
                .any { inp ->
                    inp.variables.isNotEmpty()
                }
        }

    @Test
    fun `excludes parent fields from client operations`(): Unit =
        runBlocking {
            val schema = """
                extend type Query { foo: Foo }
                type Foo { x: Int, bar: Bar }
                type Bar { parent: Foo @parent, y: Int }
            """.asViaductSchema
            val cfg = Config.default +
                (FieldSelectionWeight to CompoundingWeight(1.0, 3))

            Arb.viaductExecutionInput(schema, cfg).forAll { input ->
                Parser()
                    .parseDocument(input.operationText)
                    .allChildrenOfType<Field>()
                    .none { it.name == "parent" }
            }
        }
}
