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
    fun `Arb_nodeResolverExecutor -- SelectiveResolverWeight`(): Unit =
        runBlocking {
            Exhaustive.of(0.0, 1.0)
                .forAll { weight ->
                    val instr = NodeResolver.Factory.Instrumented()
                    Arb.nodeResolverExecutor(
                        schema,
                        Config.default +
                            (SelectiveResolverWeight to weight) +
                            (NodeResolverFactory to instr)
                    ).bind()

                    instr.recorder.arg.selective == (weight == 1.0)
                }
        }
}
