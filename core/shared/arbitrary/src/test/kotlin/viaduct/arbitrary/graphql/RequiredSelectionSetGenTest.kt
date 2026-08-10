@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.language.AstPrinter
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.CompoundingWeight
import viaduct.arbitrary.common.CompoundingWeight.Companion.Never
import viaduct.arbitrary.common.CompoundingWeight.Companion.Once
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.RequiredSelectionSetRegistry
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.engine.runtime.select.allCoords
import viaduct.engine.runtime.select.reachableObjects
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreAcyclic
import viaduct.engine.runtime.tenantloading.RequiredSelectionsValidationCtx

class RequiredSelectionSetGenTest : KotestPropertyBase() {
    @Test
    fun `generator does not generate interface-mediated cycles`(): Unit =
        runBlocking {
            val schema = """
                extend type Query { a:A }
                interface I { x:Int }
                type A implements I { x:Int, y:Int @resolver }
                type B implements I { x:Int @resolver, i:I }
            """.trimIndent().asViaductSchema

            val cfg = Config.default +
                (RequiredSelectionSetWeight to Once) +
                (FieldSelectionWeight to CompoundingWeight(1.0, 2)) +
                (InlineFragmentWeight to CompoundingWeight(1.0, 2)) +
                (FragmentSpreadWeight to Never) +
                (VariableWeight to 0.0) +
                (AppliedDirectiveWeight to Never)

            val arb = arbitrary { rs ->
                RequiredSelectionSetGen(ViaductGenEnv(schema, cfg, rs))
            }

            arb.checkAll { gen ->
                val aY = gen.gen("A" to "y", typeCondition = "A", forChecker = false, depth = 0)!!
                val bX = gen.gen("B" to "x", typeCondition = "B", forChecker = false, depth = 0)!!
                val registry = requiredSelectionSetRegistry(
                    ("A" to "y") to aY,
                    ("B" to "x") to bX,
                )

                RequiredSelectionsAreAcyclic(schema).validate(
                    RequiredSelectionsValidationCtx("A", "y", registry)
                )
            }
        }

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
                rss!!
            }

            val factory = EngineSelectionSetFactoryImpl(schema)
            arb.checkAll { rss ->
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
    fun `generated dependencies precede their owner`(): Unit =
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
                (RequiredSelectionSetWeight to Once)

            val arb = arbitrary { rs ->
                val env = ViaductGenEnv(schema, cfg, rs)
                val gen = RequiredSelectionSetGen(env)
                val (owner, typeCondition, forChecker) = Arb.of(
                    Triple<TypeOrFieldCoordinate, String, Boolean>("Query" to "y", "Query", false),
                    Triple<TypeOrFieldCoordinate, String, Boolean>("Foo" to "s", "Foo", false),
                    Triple<TypeOrFieldCoordinate, String, Boolean>("Foo" to null, "Foo", true),
                ).next(rs)
                val rss = gen.gen(
                    tfc = owner,
                    typeCondition = typeCondition,
                    forChecker = forChecker,
                    depth = 0,
                )!!

                Triple(env.coordinateIndex, owner, rss)
            }

            arb.checkAll { (coordinateIndex, owner, rss) ->
                val dependencies = rss.dependencies(schema)
                assertTrue(
                    dependencies.all { it in coordinateIndex.before(owner) },
                    "${AstPrinter.printAst(rss.selections.toDocument())}\n" +
                        "owner=$owner dependencies=$dependencies"
                )
            }
        }

    @Test
    fun `no selectable dependencies returns null`() {
        val schema = "extend type Query { x:Int @resolver }".asViaductSchema
        val cfg = Config.default +
            (RequiredSelectionSetWeight to Once) +
            (BanSelectionCoordinates to setOf("Query" to "__typename"))
        val gen = RequiredSelectionSetGen(ViaductGenEnv(schema, cfg, randomSource))

        assertNull(
            gen.gen(
                tfc = "Query" to "x",
                typeCondition = "Query",
                forChecker = false,
                depth = 0,
            )
        )
    }

    private fun RequiredSelectionSet.dependencies(schema: ViaductSchema): Set<TypeOrFieldCoordinate> {
        val factory = EngineSelectionSetFactoryImpl(schema)
        return buildSet {
            val selectionSet = factory.engineSelectionSet(selections, emptyMap())
            addAll(selectionSet.allCoords(schema).filterNot { (_, fieldName) -> fieldName.startsWith("__") })
            addAll(selectionSet.reachableObjects(schema).map { typeName -> typeName to null })
            variablesResolvers
                .mapNotNull { it.requiredSelectionSet }
                .forEach { addAll(it.dependencies(schema)) }
        }
    }

    private fun requiredSelectionSetRegistry(vararg entries: Pair<TypeOrFieldCoordinate, RequiredSelectionSet>): RequiredSelectionSetRegistry =
        object : RequiredSelectionSetRegistry {
            private val fieldResolverEntries = entries.groupBy({ it.first }, { it.second })

            override fun getFieldResolverRequiredSelectionSets(
                typeName: String,
                fieldName: String
            ): List<RequiredSelectionSet> = fieldResolverEntries[typeName to fieldName].orEmpty()

            override fun getFieldCheckerRequiredSelectionSets(
                typeName: String,
                fieldName: String
            ): List<RequiredSelectionSet> = emptyList()

            override fun getTypeCheckerRequiredSelectionSets(typeName: String): List<RequiredSelectionSet> = emptyList()
        }
}
