@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.language.AstPrinter
import io.kotest.property.arbitrary.arbitrary
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.CompoundingWeight.Companion.Once
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.engine.runtime.select.allCoords

class RequiredSelectionSetGenTest : KotestPropertyBase() {
    @Test
    fun `generator does not produce cycles through abstract type expansion`(): Unit =
        runBlocking {
            /**
             * Consider the case of this schema:
             *   interface I { x:Int }
             *   type Foo implements I { x:Int @resolver }
             *
             * The RSS for Foo.x will form an illegal cycle if it is able to select Foo.x
             * This selections can be laundered through an abstract type, such as:
             *   fragment Main on Foo { ... on I { x } }
             *
             * This test asserts that these kinds of cycles are not generated.
             */
            val schema = """
                interface I { x:Int }
                type Foo implements I { x:Int @resolver }
            """.trimIndent().asViaductSchema
            val cfg = Config.default +
                (RequiredSelectionSetWeight to Once) +
                (InlineFragmentWeight to Once) +
                (FragmentSpreadWeight to Once)

            val arb = arbitrary { rs ->
                val env = ViaductGenEnv(schema, cfg, rs)
                val gen = RequiredSelectionSetGen(env)
                val rss = gen.gen(
                    tfc = "Foo" to "x",
                    typeCondition = "Foo",
                    forChecker = false,
                    depth = 0,
                )
                gen.graph to rss!!
            }

            val factory = EngineSelectionSetFactoryImpl(schema)
            arb.checkAll { (graph, rss) ->
                graph.assertAcyclic()

                val selectionSet = factory.engineSelectionSet(rss.selections, emptyMap())
                assertTrue(
                    "Foo" to "x" !in selectionSet.allCoords(schema),
                    AstPrinter.printAst(rss.selections.toDocument())
                )

                assertTrue(
                    "I" to "x" !in selectionSet.allCoords(schema),
                    AstPrinter.printAst(rss.selections.toDocument())
                )
            }
        }

    @Test
    fun `generator does not produce cycles through blocked reachable object coordinates`(): Unit =
        runBlocking {
            val schema = """
            extend type Query {
                y: Int @resolver
                foo: Foo
                safe: Int
            }

            type Foo {
                s: Int @resolver
                safe: Int
                query: Query
            }
            """.trimIndent().asViaductSchema
            val cfg = Config.default +
                (RequiredSelectionSetWeight to Once) +
                (BanSelectionCoordinates to setOf("Foo" to "__typename", "Query" to "__typename"))

            val arb = arbitrary { rs ->
                val env = ViaductGenEnv(schema, cfg, rs)
                val gen = RequiredSelectionSetGen(env)

                // populate the RSS graph with entries for a Foo checker
                gen.gen(
                    tfc = "Foo" to null,
                    typeCondition = "Foo",
                    forChecker = true,
                    depth = 0,
                )!!

                // populate an additional Foo.s RSS to increase pressure to create cycles
                gen.gen(
                    tfc = "Foo" to "s",
                    typeCondition = "Foo",
                    forChecker = false,
                    depth = 0,
                )!!

                // generate the actual rss we want to assert on
                gen.gen(
                    tfc = "Query" to "y",
                    typeCondition = "Query",
                    forChecker = false,
                    depth = 0,
                )!!

                gen.graph
            }

            arb.checkAll { graph ->
                graph.assertAcyclic()
            }
        }
}
