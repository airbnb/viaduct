@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.Exhaustive
import io.kotest.property.arbitrary.next
import io.kotest.property.exhaustive.of
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.engine.api.ViaductSchema

class NodeResolversTest : KotestPropertyBase() {
    private val schema = """
        type Foo implements Node @resolver { id:ID! x:Int y:Int @resolver }
        type Bar { x:ID! }
    """.asViaductSchema

    @Test
    fun `Arb_nodeResolverExecutor -- simple`(): Unit =
        runBlocking {
            // without typename
            Arb.nodeResolverExecutor(schema).forAll {
                it.typeName == "Foo"
            }

            // with typename
            Arb.nodeResolverExecutor(schema, "Foo").forAll {
                it.typeName == "Foo"
            }
        }

    @Test
    fun `Arb_nodeResolverExecutor with typename -- not a node`() {
        assertThrows<IllegalArgumentException> {
            Arb.nodeResolverExecutor(schema, "Bar")
        }
    }

    @Test
    fun `Arb_nodeResolverExecutor with typename -- undefined type`() {
        assertThrows<IllegalArgumentException> {
            Arb.nodeResolverExecutor(schema, "Missing")
        }
    }

    @Test
    fun `Arb_nodeResolverExecutor -- instrumentation`(): Unit =
        runBlocking {
            val instr = NodeResolver.Factory.Instrumented()

            Arb.nodeResolverExecutor(
                schema,
                Config.default + (NodeResolverFactory to instr)
            ).next(randomSource)

            assertTrue("Foo" in instr.resolvers)
            assertEquals("Foo", instr.recorder.arg.typeName)
        }

    @Test
    fun `Arb_nodeResolverExecutor -- declared resolver selectivity`(): Unit =
        runBlocking {
            Exhaustive.of(
                "type Foo implements Node @resolver { id:ID! x:Int }".asViaductSchema to false,
                ViaductSchema(
                    """
                    directive @resolver(isSelective: Boolean! = false) on FIELD_DEFINITION | OBJECT
                    type Query { placeholder: Int }
                    interface Node { id: ID! }
                    type Foo implements Node @resolver(isSelective: true) { id:ID! x:Int }
                    """.asSchema
                ) to true
            )
                .forAll { (schema, expectedSelective) ->
                    val instr = NodeResolver.Factory.Instrumented()
                    Arb.nodeResolverExecutor(
                        schema,
                        Config.default +
                            (NodeResolverFactory to instr)
                    ).bind()

                    instr.recorder.arg.selective == expectedSelective
                }
        }
}
