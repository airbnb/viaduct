package viaduct.engine.api.spi

import viaduct.engine.api.Coordinate

/**
 * Provides selectivity metadata for field coordinates that do not have resolver dispatcher metadata.
 */
fun interface FieldSelectivityProvider {
    /** return `true` if the field at [coordinate] is selective */
    fun isSelective(coordinate: Coordinate): Boolean

    /** Returns a provider that is selective when either this provider or [other] is selective. */
    infix fun or(other: FieldSelectivityProvider): FieldSelectivityProvider =
        FieldSelectivityProvider {
                coordinate ->
            isSelective(coordinate) || other.isSelective(coordinate)
        }

    companion object {
        /** An instance of [FieldSelectivityProvider] that never returns true. */
        val Never: FieldSelectivityProvider = Const(false)

        /** An instance of [FieldSelectivityProvider] that always returns true. */
        val Always: FieldSelectivityProvider = Const(true)
    }

    private class Const(private val value: Boolean) : FieldSelectivityProvider {
        override fun isSelective(coordinate: Coordinate): Boolean = value
    }
}
