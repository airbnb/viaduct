package viaduct.invariants

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldBeEmpty
import org.junit.jupiter.api.Test
import viaduct.invariants.FailureCollector.Companion.EMPTY_ARGS

class BasicTests {
    @Test
    fun basicTest() {
        val subject = FailureCollector()
        subject.assertEmptyMultiline("Invariant Checker was not empty")
        for (i in 1..10) {
            subject.addFailure(null, "f$i", EMPTY_ARGS)
            subject shouldHaveSize i
        }

        subject.map { it.message } shouldContainExactly listOf("f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10")
    }

    @Test
    fun noContextTest() {
        val subject = FailureCollector()
        subject.addFailure(null, "msg", EMPTY_ARGS)
        val f = subject.iterator().next()
        f.context.shouldBeEmpty()
    }

    @Test
    fun contextTest() {
        val subject = FailureCollector()
        subject.pushContext("c1")
        subject.addFailure(null, "m1", EMPTY_ARGS)
        subject.pushContext("c2")
        subject.addFailure(null, "m2", EMPTY_ARGS)
        subject.popContext()
        subject.addFailure(null, "m3", EMPTY_ARGS)
        subject.pushContext("c3")
        subject.addFailure(null, "m4", EMPTY_ARGS)
        subject.popContext()
        subject.addFailure(null, "m5", EMPTY_ARGS)
        subject.popContext()
        subject.addFailure(null, "m6", EMPTY_ARGS)
        subject.map { it.message } shouldContainExactly listOf("m1", "m2", "m3", "m4", "m5", "m6")
        subject.map { it.context } shouldContainExactly listOf("c1", "c1.c2", "c1", "c1.c3", "c1", "")
    }

    @Test
    fun withContextTest() {
        val subject = FailureCollector()
        subject.withContext("c1") {
            subject.addFailure(
                null,
                "msg",
                EMPTY_ARGS
            )
        }
        subject.withContext("c2") {
            subject.addFailure(
                null,
                "msg",
                EMPTY_ARGS
            )
        }
        subject.map { it.context } shouldContainExactly listOf("c1", "c2")
    }

    @Test
    fun labelTests() {
        val subject = FailureCollector()
        subject.addFailure(null, "L1", EMPTY_ARGS)
        subject.addFailure(null, "L-2", EMPTY_ARGS)
        subject.addFailure(null, "L3: additional explanation", EMPTY_ARGS)
        subject.map { it.label } shouldContainExactly listOf("L1", "L-2", "L3")
    }
}
