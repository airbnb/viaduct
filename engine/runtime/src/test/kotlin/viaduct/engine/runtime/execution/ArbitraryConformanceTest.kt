@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.schema.idl.SchemaPrinter
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.take
import io.kotest.property.checkAll
import kotlin.math.sqrt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.CompoundingWeight
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.flatten
import viaduct.arbitrary.common.randomSource
import viaduct.arbitrary.graphql.EnumTypeSize
import viaduct.arbitrary.graphql.FragmentSpreadWeight
import viaduct.arbitrary.graphql.GenInterfaceStubsIfNeeded
import viaduct.arbitrary.graphql.InputObjectTypeSize
import viaduct.arbitrary.graphql.ListValueSize
import viaduct.arbitrary.graphql.ObjectTypeSize
import viaduct.arbitrary.graphql.OperationCount
import viaduct.arbitrary.graphql.ResolverExceptionWeight
import viaduct.arbitrary.graphql.SchemaSize
import viaduct.arbitrary.graphql.graphQLDocument
import viaduct.arbitrary.graphql.graphQLExecutionInput
import viaduct.arbitrary.graphql.graphQLSchema

private const val iter = 5_000

/**
 * This class is a tool for mining and investigating conformance bugs.
 *
 * It has a workflow that is different from other test classes:
 *
 * 1. create a new test method with the schema that you want to test
 *
 * 2. modify the [Config] used by the resolvers and the document generator to focus on the
 *   feature space that you are interested in.
 *
 * 3. If your test fails, then simplify the failing input by continuing to tune your Config
 *   and increasing the number of iterations that your test runs for (e.g., x10 or x100 the
 *   default iterations).
 *   The test runner, which always runs all requested iterations and returns the
 *   simplest failing input, will use the increased iteration count to find progressively
 *   simpler failure cases.
 *
 * 4. When you have a simple-enough failure to work with, put the seed value of the failing
 *   case into the super call to [KotestPropertyBase]. This will make your failure repeatable,
 *   though also make sure to not check this value in.
 *
 * 5. Copy-paste your simplified failure case into a manual check method inside your Conformer:
 *   ```kotlin
 *   Conformer("...", cfg) {
 *     check(
 *       "a document string that failed",
 *       mapOf("variableName" to ...)
 *     )
 *   }
 *   ```
 *   Continue to simplify the failure case into something that is human-readable and contains
 *   no features that are unrelated to the failure.
 *
 * 6. Fix the bug!
 *
 * 7. Move the simplified manual `check` that you added into another test suite. If possible,
 * use fixed wiring rather than the arb-based wiring used here.
 */
@ExperimentalCoroutinesApi
class ArbitraryConformanceTest : KotestPropertyBase() {
    val cfg = Config.default

    @Test
    fun `trivial schema -- conformance`() {
        Conformer("type Query { x: Int }", cfg) {
            Arb.viaductExecutionInput(schema, cfg).checkAll(iter)
        }
    }

    @Test
    fun `simple schema -- conformance`() {
        Conformer(
            """
                type Query { x: Int, y:Int!, z:[Int] }
                type Mutation { x:Int, y:Int!, z:[Int] }
                type Subscription { x:Int, y:Int!, z:[Int] }
            """.trimIndent(),
            cfg
        ) {
            Arb.viaductExecutionInput(schema, cfg).checkAll(iter)
        }
    }

    @Test
    fun `mutation schema`() {
        Conformer(
            """
                type Mutation { x:Int, y(a:Int):String, obj:Obj, objs:[Obj!] }
                type Obj { a:Int, next:Obj }
                type Query { placeholder: Int }
            """.trimIndent(),
            cfg
        ) {
            Arb.viaductExecutionInput(schema, cfg).checkAll(iter)
        }
    }

    @Test
    fun `trivial schema -- re-execute document with different variables`() {
        Conformer("type Query {x: Int}", cfg) {
            val inputs = Arb.graphQLDocument(schema.schema, cfg)
                .map { doc ->
                    Arb.graphQLExecutionInput(schema.schema, doc, cfg)
                        .asViaductExecutionInput(schema)
                        .take(10, randomSource())
                        .toList()
                }.flatten()

            inputs.checkAll(iter)
        }
    }

    @Test
    fun `field merging -- objects`() {
        Conformer("type Query { x: Int, q:Query }", cfg) {
            Arb.viaductExecutionInput(schema, cfg).checkAll(iter)
        }
    }

    @Test
    fun `field merging -- fragments`() {
        val cfg = cfg + (FragmentSpreadWeight to CompoundingWeight(.2, 10)) + (OperationCount to 1..1)
        Conformer("type Query { x:Int, q:Query }", cfg) {
            Arb.viaductExecutionInput(schema, cfg).checkAll(iter)
        }
    }

    @Test
    fun `field errors`() {
        val cfg = cfg + (ResolverExceptionWeight to .3)
        Conformer("type Query { x:Int, y:Int, q:Query }", cfg) {
            Arb.viaductExecutionInput(schema, cfg).checkAll(iter, checkNoModernErrors = false)
        }
    }

    @Test
    fun `field merging -- unions`() {
        Conformer(
            """
                type Foo { x:Int, u:Union }
                type Bar { y:Int, u:Union }
                union Union = Foo | Bar
                type Query { u(arg:Int): Union }
            """.trimIndent(),
            cfg
        ) {
            Arb.viaductExecutionInput(schema, cfg).checkAll(iter)
        }
    }

    @Test
    fun `field merging -- interfaces`() {
        Conformer(
            """
                interface I_A { a:Int }
                interface I_AA implements I_A { a:Int, aa:Int }
                interface I_B { b:Int }
                type Obj_A implements I_A { a:Int, x:Int }
                type Obj_AA_B implements I_A & I_AA & I_B { a:Int, aa:Int, b:Int, x:Int }
                type Obj_B implements I_B { b:Int, x:Int }

                type Query { a(a:Int):I_A,  aa(aa:Int):I_AA,  b(b:Int): I_B }
            """.trimIndent(),
        ) {
            Arb.viaductExecutionInput(schema, cfg).checkAll(iter)
        }
    }

    @Test
    fun `field merging -- overlapping unrelated interfaces`() {
        // assert that we can merge selections between A1 and A2, which define fields
        // that pass the spec's SameResponseShape rule without being directly related
        // to each other
        // @see https://spec.graphql.org/draft/#SameResponseShape()
        Conformer(
            """
                interface A1 { a:Int, x:Int }
                interface A2 { a:Int, y:Int }
                type Obj implements A1 & A2 { a:Int, x:Int, y:Int }
                type Query { a1(a:Int):A1,  a2(a:Int):A2 }
            """.trimIndent()
        ) {
            Arb.viaductExecutionInput(schema, cfg).checkAll(iter)
        }
    }

    @Test
    fun `input conformance`() {
        Conformer(
            """
                input Inp { a:Int, b:[Int] c:Int=0, d:Int!=0, inp:Inp }
                type Query { x(a:Int, b:[Int], c:Int=0, d:Int!=0, inp:Inp):Int }
            """.trimIndent()
        ) {
            Arb.viaductExecutionInput(schema, cfg).checkAll()
        }
    }

    @Test
    fun `complex schema -- conforms`() {
        Conformer(
            """
                interface Node { id: ID! }
                input Inp { x:Int, y:String!, inp:Inp }
                input Inp2 @oneOf { x:Int, y:String, inp:Inp2 }
                type Foo implements Node { foo:Int id:ID!}
                type Bar { s:String }
                union U = Foo | Bar
                type Query {
                    x:Int
                    u(arg:Int!, inp:Inp!, inp2:Inp2):U
                    node(id:ID!): Node
                }
            """.trimIndent(),
            cfg
        ) {
            Arb.viaductExecutionInput(schema, cfg).checkAll(iter)
        }
    }

    @Test
    fun oneof() {
        Conformer(
            """
                input Inp @oneOf { a:Int, b:[Int], c:Inp, d:[Inp!] }
                type Query { q(inp:Inp):Query }
            """.trimIndent()
        ) {
            Arb.viaductExecutionInput(schema, cfg).checkAll(iter)
        }
    }

    @Test
    fun `arb arb arb`(): Unit =
        runBlocking {
            /** test execution of arbitrary requests against arbitrary schemas that use arbitrary wiring */

            // use the default config, but tune it down to create smaller schemas
            val cfg = cfg +
                (SchemaSize to 20) +
                (ObjectTypeSize to 1..5) +
                (EnumTypeSize to 1..5) +
                (InputObjectTypeSize to 1..5) +
                (ListValueSize to 0..2) +
                (GenInterfaceStubsIfNeeded to true)

            val dim = sqrt(iter.toDouble()).toInt()
            Arb.graphQLSchema(cfg).checkAll(dim) { schema ->
                Conformer(SchemaPrinter().print(schema)) {
                    Arb.viaductExecutionInput(this.schema).checkAll(dim)
                    markSuccess()
                }
            }
        }

    /**
     * This test can be un-disabled and run to check a test case using an infinite sequence.
     * of seed values.
     * This allows running a large number of test cases to find low-probability conformance bugs.
     *
     * This test has the potential to run forever and should not be enabled in master.
     */
    @Test
    @Disabled
    fun `debug -- seed march`() {
        var i = 0
        var iters = 0
        while (true) {
            val test = ArbitraryConformanceTest()
            println("SEED: ${PropertyTesting.defaultSeed}  i=$i   iters=$iters")
            test.`arb arb arb`()
            i += 1
            iters += iter
        }
    }
}
