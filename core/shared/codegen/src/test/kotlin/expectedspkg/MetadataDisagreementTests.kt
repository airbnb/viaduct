@file:Suppress("UNUSED", "UNUSED_PARAMETER")

package expectedspkg

class MetadataDisagreementTests {
    open class Modality

    interface Names

    class Constructors(
        a: Int
    ) {
        constructor(b: String) : this(0)
        constructor(a: Double) : this(0)
    }

    abstract class Functions {
        open fun f1(a: String) {}

        abstract fun f2(): Int

        protected abstract fun f3()
    }

    class Properties(
        p1: String
    ) {
        var p2: Int = 1
        private var p3: Int? = 2
    }

    enum class EnumEntries { A, B }

    class ExtraNestedClass
}
