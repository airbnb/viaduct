@file:Suppress("UNUSED", "UNUSED_PARAMETER")

package actualspkg

class MetadataAgreementTests {
    class EmptyClass

    class WithCtors(
        val foo: Int
    ) {
        internal constructor(foo: Int?, bar: Int = 1) : this(bar)
        protected constructor(bar: WithCtors? = null) : this(2)
    }

    abstract class WithProperties {
        var p1: Long = 0L
        abstract var p2: String?
        private val p3: WithFunctions? = null
        internal val p4: Short = 2
    }

    interface WithFunctions {
        fun m1(): Int

        private fun m1(a1: Int): Int? = 3

        suspend fun m1(
            a1: Int,
            a2: String?,
            a3: Long?
        ): Int

        fun m1(a1: WithFunctions?): Int

        fun m2(): WithProperties?
    }

    private interface NestedClasses {
        class EmptyClass

        interface EmptyInterface

        @Testing
        interface AnnotatedInterface {
            fun m1(): Int

            fun m2(a1: Int): Int

            class DoubleNestedEmptyClass
        }
    }

    data class DataClass(
        val a1: WithProperties,
        val a2: Int,
        var a3: Long? = null,
        private var a4: WithFunctions?
    ) {
        var p1: String? = null
    }

    enum class EnumClass { A, B }
}
