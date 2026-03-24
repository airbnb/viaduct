package viaduct.schema.base

class ToArgumentMapException : Exception()

interface ViaductInputType {
    fun toArgumentMap(): Map<String, Any?> = throw ToArgumentMapException()

    fun toArgumentMapWithNulls(): Map<String, Any?> = throw ToArgumentMapException()
}
