package viaduct.engine.api.spi

import viaduct.engine.api.Coordinate

/**
 * Provides selectivity metadata for field coordinates that do not have resolver dispatcher metadata.
 */
fun interface FieldSelectivityProvider {
    /** return `true` if the field at [coordinate] is selective */
    fun isSelective(coordinate: Coordinate): Boolean

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
