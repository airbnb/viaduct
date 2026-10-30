package viaduct.engine.runtime2.execution

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class Engine2AlternatePairingTest {
    @Test
    fun `matched pairs have no violations`() {
        val marks = AlternateMarks(
            disabled = mapOf("a" to Engine2DisabledKind.ALT, "b" to Engine2DisabledKind.ALT),
            alternates = listOf(AlternateRef("ALTERNATIVE a", "a"), AlternateRef("ALTERNATIVE b", "b")),
        )
        assertEquals(emptyList(), alternatePairingViolations(marks))
    }

    @Test
    fun `ALT original without alternate is reported`() {
        val marks = AlternateMarks(disabled = mapOf("a" to Engine2DisabledKind.ALT), alternates = emptyList())
        assertEquals(listOf("ALT original 'a' has 0 alternates, expected 1"), alternatePairingViolations(marks))
    }

    @Test
    fun `ALT original with two alternates is reported`() {
        val marks = AlternateMarks(
            disabled = mapOf("a" to Engine2DisabledKind.ALT),
            alternates = listOf(AlternateRef("x", "a"), AlternateRef("y", "a")),
        )
        assertEquals(listOf("ALT original 'a' has 2 alternates, expected 1"), alternatePairingViolations(marks))
    }

    @Test
    fun `alternate targeting a test that is not disabled is reported`() {
        val marks = AlternateMarks(disabled = emptyMap(), alternates = listOf(AlternateRef("x", "a")))
        assertEquals(
            listOf("alternate 'x' targets 'a', which is not a disabled test"),
            alternatePairingViolations(marks)
        )
    }

    @Test
    fun `renaming an ALT original without updating its alternate fails both sides`() {
        assertEquals(
            listOf(
                "ALT original 'new name' has 0 alternates, expected 1",
                "alternate 'replacement' targets 'old name', which is not a disabled test",
            ),
            alternatePairingViolations(collectAlternateMarks(RenamedOriginalFixture::class.java))
        )
    }

    @Test
    fun `ALT original without any alternate fails`() {
        assertEquals(
            listOf("ALT original 'lonely original' has 0 alternates, expected 1"),
            alternatePairingViolations(collectAlternateMarks(MissingAlternateFixture::class.java))
        )
    }

    @Test
    fun `alternate targeting a TODO or NA test is accepted`() {
        assertEquals(emptyList(), alternatePairingViolations(collectAlternateMarks(AlternateOnOtherKindsFixture::class.java)))
    }

    @Test
    fun `TODO and NA tests do not require alternates`() {
        assertEquals(emptyList(), alternatePairingViolations(collectAlternateMarks(UnpairedKindsFixture::class.java)))
    }

    @Test
    fun `pairs split across nested classes are matched`() {
        assertEquals(emptyList(), alternatePairingViolations(collectAlternateMarks(NestedPairFixture::class.java)))
    }

    @Test
    fun `pairs inherited from an abstract base are matched`() {
        assertEquals(emptyList(), alternatePairingViolations(collectAlternateMarks(InheritedPairFixture::class.java)))
    }

    @Test
    fun `renaming an ALT original in an abstract base fails both sides`() {
        assertEquals(
            listOf(
                "ALT original 'new name' has 0 alternates, expected 1",
                "alternate 'replacement' targets 'old name', which is not a disabled test",
            ),
            alternatePairingViolations(collectAlternateMarks(InheritedRenamedFixture::class.java))
        )
    }

    @Test
    fun `collects marks from a class and its nested classes`() {
        val marks = collectAlternateMarks(Engine2FieldPolicyCheckContractTest::class.java)
        assertEquals(listOf("field should fail even if node passes"), marks.disabledAlt)
        assertEquals(
            listOf(AlternateRef("ALTERNATIVE field should fail even if node passes", "field should fail even if node passes")),
            marks.alternates
        )
    }

    private class RenamedOriginalFixture {
        @Engine2Disabled(Engine2DisabledKind.ALT, "r")
        fun `new name`() = Unit

        @Engine2Alternate(original = "old name")
        fun replacement() = Unit
    }

    private class MissingAlternateFixture {
        @Engine2Disabled(Engine2DisabledKind.ALT, "r")
        fun `lonely original`() = Unit
    }

    private class AlternateOnOtherKindsFixture {
        @Engine2Disabled(Engine2DisabledKind.TODO, "r")
        fun `todo original`() = Unit

        @Engine2Disabled(Engine2DisabledKind.NA, "r")
        fun `na original`() = Unit

        @Engine2Alternate(original = "todo original")
        fun `for todo`() = Unit

        @Engine2Alternate(original = "na original")
        fun `for na`() = Unit
    }

    private class UnpairedKindsFixture {
        @Engine2Disabled(Engine2DisabledKind.TODO, "r")
        fun `todo original`() = Unit

        @Engine2Disabled(Engine2DisabledKind.NA, "r")
        fun `na original`() = Unit
    }

    private abstract class PairedCases {
        @Engine2Disabled(Engine2DisabledKind.ALT, "r")
        fun original() = Unit

        @Engine2Alternate(original = "original")
        fun alternate() = Unit
    }

    private class InheritedPairFixture {
        inner class Runner : PairedCases()
    }

    private abstract class RenamedCases {
        @Engine2Disabled(Engine2DisabledKind.ALT, "r")
        fun `new name`() = Unit

        @Engine2Alternate(original = "old name")
        fun replacement() = Unit
    }

    private class InheritedRenamedFixture {
        inner class Runner : RenamedCases()
    }

    private class NestedPairFixture {
        @Engine2Disabled(Engine2DisabledKind.ALT, "r")
        fun original() = Unit

        inner class Nested {
            @Engine2Alternate(original = "original")
            fun alternate() = Unit
        }
    }
}
