package viaduct.invariants

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.util.regex.Pattern
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo

class AssertionTests {
    @Test
    fun isTrueTests() {
        val subject = InvariantChecker()
        subject.isTrue(true, "T1")
        subject.isTrue(false, "T2")
        subject.assertContainsExactly(
            Failure("", "T2", null)
        )
    }

    @Test
    fun isFalseTests() {
        val subject = InvariantChecker()
        subject.isFalse(true, "T1")
        subject.isFalse(false, "T2")
        subject shouldContainExactly listOf(Failure("", "T1", null))
    }

    @Test
    fun isSameInstanceAsTests() {
        val subject = InvariantChecker()
        val testValue = "test-value"
        subject.isSameInstanceAs(subject, subject, "T1")
        subject.isSameInstanceAs(subject, testValue, "T2")
        subject.isSameInstanceAs(testValue, testValue, "T3")
        subject.isSameInstanceAs(
            Failure("c", "m", "d"),
            Failure("c", "m", "d"),
            "T4"
        )

        subject.assertContainsExactly(
            Failure("", "T2", ".*"),
            Failure("", "T4", ".*")
        )
    }

    @Test
    fun testIsNotSameInstanceAs() {
        val subject = InvariantChecker()
        val o = Any()
        subject.isNotSameInstanceAs(o, o, "f0")
        subject.isNotSameInstanceAs(null, null, "f1")
        subject.isNotSameInstanceAs(o, null, "f2")
        subject.isNotSameInstanceAs(null, o, "f3")
        subject.isNotSameInstanceAs(o, Any(), "f4")
        subject.map { it.message } shouldContainExactly listOf("f0", "f1")
    }

    @Test
    fun isIntEqualToTests() {
        val subject = InvariantChecker()
        subject.isSameInstanceAs(1, 1, "T1")
        subject.isSameInstanceAs(0, 1, "T2")

        subject.assertContainsExactly(
            Failure("", "T2", ".*")
        )
    }

    @Test
    fun isEqualToTests() {
        val subject = InvariantChecker()
        subject.isEqualTo(null, null, "N1")
        subject.isEqualTo(null, subject, "N2")
        subject.isEqualTo(subject, null, "N3")
        subject.isEqualTo(subject, subject, "T1")
        val o = Any()
        subject.isEqualTo(subject, o, "T2")
        subject.isEqualTo(o, o, "T3")
        subject.isEqualTo("hi", "hi", "T4")
        subject.isEqualTo("hi", "there", "T5")
        subject.isEqualTo(Failure("c", "m", null), Failure("c", "m", "d"), "T6")
        subject.isEqualTo(Failure("c", "m", "d"), Failure("c", "m", "d"), "T7")
        subject.assertContainsExactly(
            Failure("", "N2", ".*"),
            Failure("", "N3", ".*"),
            Failure("", "T2", ".*"),
            Failure("", "T5", ".*"),
            Failure("", "T6", ".*")
        )
    }

    @Test
    fun isNotEqualToTests() {
        val subject = InvariantChecker()
        subject.isNotEqualTo(null, null, "N1")
        subject.isNotEqualTo(null, subject, "N2")
        subject.isNotEqualTo(subject, null, "N3")
        subject.isNotEqualTo(subject, subject, "T1")
        val o = Any()
        subject.isNotEqualTo(subject, o, "T2")
        subject.isNotEqualTo(o, o, "T3")
        subject.isNotEqualTo("hi", "hi", "T4")
        subject.isNotEqualTo("hi", "there", "T5")
        subject.isNotEqualTo(
            Failure("c", "m", null),
            Failure("c", "m", "d"),
            "T6"
        )
        subject.isNotEqualTo(Failure("c", "m", "d"), Failure("c", "m", "d"), "T7")
        subject.assertContainsExactly(
            Failure("", "N1", ".*"),
            Failure("", "T1", ".*"),
            Failure("", "T3", ".*"),
            Failure("", "T4", ".*"),
            Failure("", "T7", ".*")
        )
    }

    @Test
    fun isNullTests() {
        val subject = InvariantChecker()
        subject.isNull(null, "T0")
        subject.isNull(subject, "T1")
        subject.assertContainsExactly(
            Failure("", "T1", ".*")
        )
    }

    @Test
    fun isNotNullTests() {
        val subject = InvariantChecker()
        subject.isNotNull(null, "T0")
        subject.isNotNull(subject, "T1")
        subject.assertContainsExactly(
            Failure("", "T0", ".*")
        )
    }

    @Test
    fun isEmptyTests() {
        val subject = InvariantChecker()
        subject.isEmpty(subject, "T0")
        subject.isEmpty(listOf<Any?>(), "T1")
        subject.isEmpty(listOf<String?>("Howdy"), "T2")
        subject.isEmpty(subject, "T3")
        subject.isEmpty("", "T4")
        subject.isEmpty("a", "T5")
        subject.isEmpty(StringBuilder(), "T6")
        subject.isEmpty(StringBuilder().append("hi"), "T7")
        subject.assertContainsExactly(
            Failure("", "T2", ".*"),
            Failure("", "T3", ".*"),
            Failure("", "T5", ".*"),
            Failure("", "T7", ".*")
        )
    }

    @Test
    fun isNotEmptyTests() {
        val subject = InvariantChecker()
        subject.isNotEmpty(subject, "T0")
        subject.isNotEmpty(listOf<Any?>(), "T1")
        subject.isNotEmpty(listOf<String?>("Howdy"), "T2")
        subject.isNotEmpty(subject, "T3")
        subject.isNotEmpty("", "T4")
        subject.isNotEmpty("a", "T5")
        subject.isNotEmpty(StringBuilder(), "T6")
        subject.isNotEmpty(StringBuilder().append("hi"), "T7")
        subject.assertContainsExactly(
            Failure("", "T0", ".*"),
            Failure("", "T1", ".*"),
            Failure("", "T4", ".*"),
            Failure("", "T6", ".*")
        )
    }

    @Test
    fun containsTests() {
        val subject = InvariantChecker()
        subject.contains(null, listOf<String?>(), "T1")
        subject.contains(null, listOf(*arrayOfNulls<String>(1)), "T2")
        subject.contains(null, listOf<String?>("a", "b"), "T3")
        subject.contains(1.0, listOf(2.0, 1.0), "T4")
        subject.contains('a', listOf('m', 'n', 'a', 'o'), "T5")
        subject.contains('z', listOf('m', 'n', 'a', 'o'), "T6")
        subject.contains(null, listOf("alpha", "beta", null, "gamma", "delta"), "T7")
        subject.assertContainsExactly(
            Failure("", "T1", ".*"),
            Failure("", "T3", ".*"),
            Failure("", "T6", ".*")
        )
    }

    @Test
    fun isInOrderTests() {
        val subject = InvariantChecker()
        subject.isInOrder(listOf<String>(), "T1")
        subject.isInOrder(listOf("a"), "T2")
        subject.isInOrder(listOf(1, 2), "T3")
        subject.isInOrder(listOf(2.0, 1.0), "T4")
        subject.isInOrder(listOf('m', 'n', 'a', 'o'), "T5")
        subject.isInOrder(listOf("alpha", "beta", "gamma", "delta"), "T6")
        subject.assertContainsExactly(
            Failure("", "T4", ".*"),
            Failure("", "T5", ".*"),
            Failure("", "T6", ".*gamma.*not less than.*delta.*")
        )
    }

    @Test
    fun containsNoDuplicatesTests() {
        val subject = InvariantChecker()
        subject.containsNoDuplicates(listOf<String>(), "T1")
        subject.containsNoDuplicates(listOf("a"), "T2")
        subject.containsNoDuplicates(listOf(1, 2), "T3")
        subject.containsNoDuplicates(listOf(2.0, 1.0), "T4")
        subject.containsNoDuplicates(listOf('m', 'm'), "T5")
        subject.containsNoDuplicates(listOf("a", "b", "c"), "T6")
        subject.containsNoDuplicates(listOf("c", "a", "b", "a", "b", "c"), "T7")
        subject.containsNoDuplicates(listOf("c", "c", "c"), "T8")
        subject.assertContainsExactly(
            Failure("", "T5", ".*"),
            Failure("", "T7", ".*"),
            Failure("", "T8", ".*c, c.*")
        )
    }

    @Test
    fun containsExactlyGoodTests() {
        val subject = InvariantChecker()
        subject.containsExactlyElementsIn(listOf(), listOf<Int>(), "f0")
        subject.containsExactlyElementsIn(listOf("a"), listOf("a"), "f1")
        subject.containsExactlyElementsIn(listOf("a", "b"), listOf("a", "b"), "f2")
        subject.containsExactlyElementsIn(listOf("a", "b"), listOf("b", "a"), "f3")
        subject.containsExactlyElementsIn(listOf("a", "a", "b"), listOf("a", "a", "b"), "f4")
        subject.containsExactlyElementsIn(listOf("a", "a", "b"), listOf("a", "b", "a"), "f5")
        subject.containsExactlyElementsIn(listOf("a", "a", "b"), listOf("b", "a", "a"), "f6")
        subject.assertEmptyMultiline("Invariant with containsExactly in expected and actual should be empty")
    }

    @Test
    fun containsExactlyUnequalSizeTests() {
        val subject = InvariantChecker()
        subject.containsExactlyElementsIn(listOf(), listOf("a"), "f0")
        subject.containsExactlyElementsIn(listOf(), listOf("a", "b"), "f1")
        subject.containsExactlyElementsIn(listOf(), listOf("a", "b", "c"), "f2")
        subject.containsExactlyElementsIn(listOf("a"), listOf(), "f3")
        subject.containsExactlyElementsIn(listOf("a", "b"), listOf(), "f4")
        subject.containsExactlyElementsIn(listOf("a", "b", "c"), listOf(), "f5")
        subject.containsExactlyElementsIn(listOf("a"), listOf("a", "a"), "f6")
        subject.containsExactlyElementsIn(listOf("a"), listOf("a", "b"), "f7")
        subject.containsExactlyElementsIn(listOf("a"), listOf("a", "b", "c", "a"), "f8")
        subject.containsExactlyElementsIn(listOf("a", "a"), listOf("a"), "f9")
        subject.containsExactlyElementsIn(listOf("a", "b"), listOf("a"), "f10")
        subject.containsExactlyElementsIn(listOf("a", "b", "c"), listOf("a"), "f11")
        subject.containsExactlyElementsIn(listOf("a", "a", "b", "c"), listOf("a", "b", "c"), "f12")
        subject.containsExactlyElementsIn(listOf("a", "b", "c"), listOf("a", "b", "c", "a"), "f13")
        subject.map { it.message } shouldContainExactly listOf(
            "f0",
            "f1",
            "f2",
            "f3",
            "f4",
            "f5",
            "f6",
            "f7",
            "f8",
            "f9",
            "f10",
            "f11",
            "f12",
            "f13"
        )
    }

    @Test
    fun containsExactlyInexactContentTests() {
        val subject = InvariantChecker()
        subject.containsExactlyElementsIn(listOf("a"), listOf("b"), "f0")
        subject.containsExactlyElementsIn(listOf("a", "b"), listOf("a", "c"), "f1")
        subject.containsExactlyElementsIn(listOf("a", "b"), listOf("c", "a"), "f2")
        subject.containsExactlyElementsIn(listOf("a", "b", "c"), listOf("a", "b", "C"), "f3")
        subject.containsExactlyElementsIn(listOf("a", "b", "c"), listOf("a", "C", "b"), "f4")
        subject.containsExactlyElementsIn(listOf("a", "b", "c"), listOf("b", "a", "C"), "f5")
        subject.containsExactlyElementsIn(listOf("a", "b", "c"), listOf("C", "a", "b"), "f6")
        subject.containsExactlyElementsIn(listOf("a", "b", "c"), listOf("C", "b", "a"), "f7")
        subject.containsExactlyElementsIn(listOf("a", "a", "c"), listOf("a", "b", "C"), "f8")
        subject.containsExactlyElementsIn(listOf("a", "a", "c"), listOf("a", "C", "b"), "f9")
        subject.containsExactlyElementsIn(listOf("a", "a", "c"), listOf("C", "a", "b"), "f10")
        subject.containsExactlyElementsIn(listOf("a", "a", "c"), listOf("C", "b", "a"), "f11")
        subject.containsExactlyElementsIn(listOf("a", "b", "c"), listOf("e", "f", "g"), "f12")
        subject.map { it.message } shouldContainExactly listOf(
            "f0",
            "f1",
            "f2",
            "f3",
            "f4",
            "f5",
            "f6",
            "f7",
            "f8",
            "f9",
            "f10",
            "f11",
            "f12"
        )
    }

    @Test
    fun containsAtLeastPassTests() {
        val subject = InvariantChecker()
        subject.containsAtLeastElementsIn(listOf(), listOf<Int>(), "f0")
        subject.containsAtLeastElementsIn(listOf(), listOf("a"), "f1")
        subject.containsAtLeastElementsIn(listOf(1), listOf(1), "f2")
        subject.containsAtLeastElementsIn(listOf(1), listOf(2, 1), "f3")
        subject.containsAtLeastElementsIn(listOf(1, 1), listOf(1, 2, 1), "f4")
        subject.containsAtLeastElementsIn(listOf(1, 1), listOf(1, 1, 1), "f5")
        subject.containsAtLeastElementsIn(listOf(3, 1), listOf(1, 2, 3), "f6")
        subject.containsAtLeastElementsIn(
            listOf(1, 1, 2, 3),
            listOf(1, 1, 2, 3, 4, 5),
            "f7"
        )
        subject.assertEmptyMultiline("Invariant with containsAtLeast in expected and partially actual should be empty")
    }

    @Test
    fun containsAtLeastFailTests() {
        val subject = InvariantChecker()
        subject.containsAtLeastElementsIn(listOf("a"), listOf(), "f0")
        subject.containsAtLeastElementsIn(listOf("b"), listOf("a"), "f1")
        subject.containsAtLeastElementsIn(listOf(3), listOf(1, 2), "f2")
        subject.containsAtLeastElementsIn(listOf(1, 2, 1, 4), listOf(2, 1, 1, 3), "f3")
        subject.containsAtLeastElementsIn(listOf(1, 2, 3, 4, 5), listOf(1, 2), "f4")
        assert(subject.map { it.message } == listOf("f0", "f1", "f2", "f3", "f4"))
    }

    @Test
    fun containsAtMostPassTests() {
        val subject = InvariantChecker()
        subject.containsAtMostElementsIn(listOf(), listOf<Int>(), "f0")
        subject.containsAtMostElementsIn(listOf("a"), listOf(), "f1")
        subject.containsAtMostElementsIn(listOf(1), listOf(1), "f2")
        subject.containsAtMostElementsIn(listOf(2, 1), listOf(1), "f3")
        subject.containsAtMostElementsIn(listOf(1, 2, 1), listOf(1, 1), "f4")
        subject.containsAtMostElementsIn(listOf(1, 1, 1), listOf(1, 1), "f5")
        subject.containsAtMostElementsIn(listOf(1, 2, 3), listOf(1, 3), "f6")
        subject.containsAtMostElementsIn(
            listOf(1, 1, 2, 3, 4, 5),
            listOf(1, 1, 2, 3),
            "f7"
        )
        subject.assertEmptyMultiline("Invariant with containsAtMost in expected and most in actual should be empty")
    }

    @Test
    fun containsAtMostFailTests() {
        val subject = InvariantChecker()
        subject.containsAtMostElementsIn(listOf(), listOf("a"), "f0")
        subject.containsAtMostElementsIn(listOf("a"), listOf("b"), "f1")
        subject.containsAtMostElementsIn(listOf(1, 2), listOf(3), "f2")
        subject.containsAtMostElementsIn(listOf(2, 1, 1, 3), listOf(1, 2, 1, 4), "f3")
        subject.containsAtMostElementsIn(listOf(1, 2), listOf(1, 2, 3, 4, 5), "f4")
        assert(subject.map { it.message } == listOf("f0", "f1", "f2", "f3", "f4"))
    }

    /** Test for doesThrow()  */
    @Test
    fun doesThrowPassTests() {
        val subject = InvariantChecker()
        subject.doesThrow<IllegalArgumentException>("f0") {
            throw IllegalArgumentException("foo")
        }
        subject.doesThrow<IllegalArgumentException>("f1") {
            throw IllegalStateException("bar")
        }
        subject.map { it.message } shouldContainExactly listOf("f1")
    }

    @Test
    fun doesNotThrowDoesNotThrow() {
        val subject = InvariantChecker()
        subject
            .doesNotThrow("IGNORE", arrayOfNulls(0)) { 1 }
            .ifNoThrow { arg: Int? -> assert(arg == 1) }
        val o = Any()
        subject
            .doesNotThrow(
                "IGNORE"
            ) { o }
            .ifNoThrow { arg: Any? ->
                arg!!::class.java shouldBe o::class.java
            }
    }

    @Test
    fun doesNotThrowThrows(testInfo: TestInfo) {
        val subject = InvariantChecker()
        subject
            .doesNotThrow("f0") { throw NullPointerException() }
            .ifNoThrow { _ -> fail<Any>("Does not throw called its consumer.") }

        subject.map { it.message } shouldContainExactly listOf("f0")
        val details = subject.iterator().next().details
        details shouldStartWith "java.lang.NullPointerException"
        // Check that stacktrace is included
        // Using testInfo makes the test renamable and movable
        details shouldContain testInfo.testMethod.get().name
    }

    @Test
    fun testIsInstanceOfPassCases() {
        val subject = InvariantChecker()

        listOf(
            Boolean::class to true,
            Byte::class to 1.toByte(),
            Char::class to 'a',
            Double::class to 1.0,
            Float::class to 1f,
            Int::class to 1,
            Long::class to 1L,
            Short::class to 1.toShort()
        ).forEach { (cls, value) ->
            subject.isInstanceOf(cls, value, "${cls.simpleName}0")
            subject.isInstanceOf(cls.javaPrimitiveType!!.kotlin, value, "${cls.simpleName}1")
            subject.isInstanceOf(cls.javaObjectType.kotlin, value, "${cls.simpleName}2")
        }

        listOf(
            Any::class to Any(),
            Any::class to "Foo",
            String::class to "a",
            CharSequence::class to "a",
            Comparable::class to 1,
            Number::class to 1
        ).forEach { (cls, value) ->
            subject.isInstanceOf(cls, value, "${cls.simpleName}0")
        }

        subject.assertEmptyMultiline("Invariant build with same types should be empty")
    }

    @Test
    fun testIsInstanceOfNullPrimitive() {
        val subject = InvariantChecker()
        subject.isInstanceOf(Boolean::class, null, "f0")
        subject.isInstanceOf(Byte::class, null, "f1")
        subject.isInstanceOf(Char::class, null, "f2")
        subject.isInstanceOf(Double::class, null, "f3")
        subject.isInstanceOf(Float::class, null, "f4")
        subject.isInstanceOf(Int::class, null, "f5")
        subject.isInstanceOf(Long::class, null, "f6")
        subject.isInstanceOf(Short::class, null, "f7")
        subject.map { it.message } shouldContainExactly listOf("f0", "f1", "f2", "f3", "f4", "f5", "f6", "f7")
    }

    @Test
    fun testIsInstanceOfNullNonPrimitive() {
        val subject = InvariantChecker()
        subject.isInstanceOf(Boolean::class.javaObjectType.kotlin, null, "f0")
        subject.isInstanceOf(Byte::class.javaObjectType.kotlin, null, "f1")
        subject.isInstanceOf(Char::class.javaObjectType.kotlin, null, "f2")
        subject.isInstanceOf(Double::class.javaObjectType.kotlin, null, "f3")
        subject.isInstanceOf(Float::class.javaObjectType.kotlin, null, "f4")
        subject.isInstanceOf(Int::class.javaObjectType.kotlin, null, "f5")
        subject.isInstanceOf(Long::class.javaObjectType.kotlin, null, "f6")
        subject.isInstanceOf(Short::class.javaObjectType.kotlin, null, "f7")
        subject.isInstanceOf(Any::class, null, "f8")
        subject.assertEmptyMultiline("Invariant build with same types and null values should be empty")
    }

    @Test
    fun testIsInstanceOfWrongTypePrimitive() {
        val subject = InvariantChecker()
        val o = Any()
        var m = 0
        subject.isInstanceOf(Boolean::class.javaObjectType.kotlin, o, "f" + m++)
        subject.isInstanceOf(Boolean::class.javaPrimitiveType!!.kotlin, o, "f" + m++)
        subject.isInstanceOf(Byte::class.javaObjectType.kotlin, o, "f" + m++)
        subject.isInstanceOf(Byte::class.javaPrimitiveType!!.kotlin, o, "f" + m++)
        subject.isInstanceOf(Char::class.javaObjectType.kotlin, o, "f" + m++)
        subject.isInstanceOf(Char::class.javaPrimitiveType!!.kotlin, o, "f" + m++)
        subject.isInstanceOf(Double::class.javaObjectType.kotlin, o, "f" + m++)
        subject.isInstanceOf(Double::class.javaPrimitiveType!!.kotlin, o, "f" + m++)
        subject.isInstanceOf(Float::class.javaObjectType.kotlin, o, "f" + m++)
        subject.isInstanceOf(Float::class.javaPrimitiveType!!.kotlin, o, "f" + m++)
        subject.isInstanceOf(Int::class.javaObjectType.kotlin, o, "f" + m++)
        subject.isInstanceOf(Int::class.javaPrimitiveType!!.kotlin, o, "f" + m++)
        subject.isInstanceOf(Long::class.javaObjectType.kotlin, o, "f" + m++)
        subject.isInstanceOf(Long::class.javaPrimitiveType!!.kotlin, o, "f" + m++)
        subject.isInstanceOf(Short::class.javaObjectType.kotlin, o, "f" + m++)
        subject.isInstanceOf(Short::class.javaPrimitiveType!!.kotlin, o, "f" + m++)

        val expected = (0 until m).map { "f$it" }
        subject.map { it.message } shouldContainExactly expected
    }

    companion object {
        /**
         * This function checks if a failure is equivalent to another failure.
         * It will return true if it is equivalent and false if it is not.
         *
         * @receiver the actual result to be checked with.
         * @param expected a Failure object that is what to expect from the main.
         *
         * @return Boolean indicating whether is a equivalent to actual or not
         */
        private fun Failure.failureEquivalent(expected: Failure): Boolean {
            if (this.context != expected.context) return false
            if (this.message != expected.message) return false
            if (this.details == null && expected.details != null) return false
            if (this.details != null && expected.details == null) return false
            if (this.details == null) return true
            return Pattern.matches(expected.details.toString(), this.details)
        }

        fun InvariantChecker.assertContainsExactly(vararg failures: Failure) {
            this.forEachIndexed { index, actual ->
                val expected = failures[index]
                withClue("Expected Value is $expected and Actual value is $actual") {
                    actual.failureEquivalent(expected).shouldBeTrue()
                }
            }
        }
    }
}
