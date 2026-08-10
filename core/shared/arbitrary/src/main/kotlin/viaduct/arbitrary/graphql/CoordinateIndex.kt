package viaduct.arbitrary.graphql

import io.kotest.property.RandomSource
import viaduct.engine.api.ViaductSchema

/**
 * [CoordinateIndex] provides a random, stable ordering of all [TypeOrFieldCoordinate] in a schema
 * This can be used to prevent cycles in a graph, by only allowing coordinates to depend on other higher or lower coordinates
 */
interface CoordinateIndex {
    /** return the index of the provided [coord] */
    fun index(coord: TypeOrFieldCoordinate): Int

    /** return the set of [TypeOrFieldCoordinate]s with an index less than the index of the provided [coord] */
    fun before(coord: TypeOrFieldCoordinate): Set<TypeOrFieldCoordinate>

    /** return the set of [TypeOrFieldCoordinate]s with an index greater than the index of the provided [coord] */
    fun after(coord: TypeOrFieldCoordinate): Set<TypeOrFieldCoordinate>

    /** return a [Comparator] that can use this [CoordinateIndex] to order coordinates */
    val comparator: Comparator<TypeOrFieldCoordinate> get() = IndexComparator(this)

    private class Impl(
        val index: List<TypeOrFieldCoordinate>,
        val map: Map<TypeOrFieldCoordinate, Int>
    ) : CoordinateIndex {
        override fun index(coord: TypeOrFieldCoordinate): Int = requireNotNull(map[coord])

        override fun before(coord: TypeOrFieldCoordinate): Set<TypeOrFieldCoordinate> {
            val i = index(coord)
            return index.subList(0, i).toSet()
        }

        override fun after(coord: TypeOrFieldCoordinate): Set<TypeOrFieldCoordinate> {
            val i = index(coord)
            return index.subList(i + 1, index.size).toSet()
        }
    }

    @JvmInline
    private value class IndexComparator(val index: CoordinateIndex) : Comparator<TypeOrFieldCoordinate> {
        override fun compare(
            o1: TypeOrFieldCoordinate,
            o2: TypeOrFieldCoordinate
        ): Int = index.index(o1).compareTo(index.index(o2))
    }

    companion object {
        /**
         * Create a new [CoordinateIndex] from the provided [schema]
         * The returned [CoordinateIndex] will have random indices assigned using the random of [rs].
         */
        operator fun invoke(
            schema: ViaductSchema,
            rs: RandomSource
        ): CoordinateIndex {
            val index = buildList {
                addAll(schema.objectCoordinates)
                addAll(schema.compositeTypeNames)
            }

            val randomIndex = index.shuffled(rs.random)
            val map = randomIndex.withIndex().associate { (i, tfc) -> tfc to i }
            return Impl(randomIndex, map)
        }
    }
}
