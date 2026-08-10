package viaduct.arbitrary.graphql

import io.kotest.common.runBlocking
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.engine.api.ViaductSchema

class CoordinateIndexTest : KotestPropertyBase() {
    @Test
    fun `ordering is stable for the same seed`(): Unit =
        runBlocking {
            val schema = "extend type Query { x:Int, y:Int }".asViaductSchema

            Arb.long()
                .map { seed ->
                    val i1 = CoordinateIndex(schema, RandomSource.seeded(seed))
                    val i2 = CoordinateIndex(schema, RandomSource.seeded(seed))
                    i1 to i2
                }
                .checkAll { (i1, i2) ->
                    assertEquals(i1.index("Query" to "x"), i2.index("Query" to "x"))
                    assertEquals(i1.index("Query" to null), i2.index("Query" to null))
                }
        }

    @Test
    fun `indexed item is not included in before`(): Unit =
        runBlocking {
            val schema = "extend type Query { x:Int, y:Int }".asViaductSchema
            val arb = arbitrary { rs -> CoordinateIndex(schema, rs) }

            arb.checkAll { ci ->
                val beforeField = ci.before("Query" to "x")
                assertTrue("Query" to "x" !in beforeField)

                val beforeType = ci.before("Query" to null)
                assertTrue("Query" to null !in beforeType)
            }
        }

    @Test
    fun `indexed item is not included in after`(): Unit =
        runBlocking {
            val schema = "extend type Query { x:Int, y:Int }".asViaductSchema
            val arb = arbitrary { rs -> CoordinateIndex(schema, rs) }

            arb.checkAll { ci ->
                val afterField = ci.after("Query" to "x")
                assertTrue(Pair("Query", "x") !in afterField)

                val afterType = ci.after("Query" to null)
                assertTrue("Query" to null !in afterType)
            }
        }

    @Test
    fun `before items have lower index`(): Unit =
        runBlocking {
            val schema = "extend type Query { x:Int, y:Int }".asViaductSchema
            val arb = arbitrary { rs ->
                CoordinateIndex(schema, rs)
            }

            arb.forAll { ci ->
                val coord = "Query" to "x"
                val i = ci.index(coord)
                val before = ci.before(coord)
                before.all { ci.index(it) < i }
            }
        }

    @Test
    fun `after items have higher index`(): Unit =
        runBlocking {
            val schema = "extend type Query { x:Int, y:Int }".asViaductSchema
            val arb = arbitrary { rs ->
                CoordinateIndex(schema, rs)
            }

            arb.forAll { ci ->
                val coord = "Query" to "x"
                val i = ci.index(coord)
                val before = ci.after(coord)
                before.all { ci.index(it) > i }
            }
        }

    @Test
    fun `before and after are complements`(): Unit =
        runBlocking {
            val schema = "extend type Query { x:Int, y:Int }".asViaductSchema
            val allCoords = schema.allCoords
            val arb = arbitrary { rs -> CoordinateIndex(schema, rs) }

            arb.checkAll { ci ->
                val coord = "Query" to "x"
                val sum = ci.before(coord) + ci.after(coord) + coord
                assertEquals(allCoords, sum)
            }
        }

    @Test
    fun `comparator`(): Unit =
        runBlocking {
            val schema = "extend type Query { x:Int, y:Int }".asViaductSchema
            val allCoords = schema.allCoords

            val arb = arbitrary { rs ->
                val ci = CoordinateIndex(schema, rs)
                val coord1 = Arb.element(allCoords).bind()
                val coord2 = Arb.element(allCoords).bind()

                Triple(ci, coord1, coord2)
            }

            arb.forAll { (ci, coord1, coord2) ->
                when (ci.comparator.compare(coord1, coord2)) {
                    -1 -> ci.index(coord1) < ci.index(coord2)
                    0 -> coord1 == coord2
                    else -> ci.index(coord1) > ci.index(coord2)
                }
            }
        }

    private val ViaductSchema.allCoords: Set<TypeOrFieldCoordinate>
        get() = buildSet {
            addAll(objectCoordinates)
            addAll(compositeTypeNames)
        }
}
