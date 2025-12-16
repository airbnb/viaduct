@file:Suppress("UNUSED", "UNUSED_PARAMETER")

package actualspkg

class MetadataDisagreementTests {
    class Modality

    interface Namess

    class Constructors(
        a: Int?
    ) {
        @Testing
        constructor(b: String) : this(0)
        constructor(a: Double = 1.0) : this(0)
    }

    abstract class Functions {
        abstract fun f1(a: String)

        abstract fun f2(): Long

        abstract fun f3()
    }

    class Properties(
        val p1: String
    ) {
        private var p2: Int = 1
        private var p3: Int = 2
    }

    enum class EnumEntries { A, C }
}
