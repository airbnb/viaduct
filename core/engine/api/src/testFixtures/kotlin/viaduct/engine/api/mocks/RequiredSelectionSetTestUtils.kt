package viaduct.engine.api.mocks

import graphql.language.AstPrinter
import viaduct.engine.api.RequiredSelectionSet

/**
 * Test utility for comparing RequiredSelectionSet instances for value equality.
 */
fun RequiredSelectionSet.isEqualTo(other: RequiredSelectionSet?): Boolean {
    if (other == null) return false
    if (this === other) return true

    return this.selections == other.selections &&
        this.variablesResolvers == other.variablesResolvers &&
        this.forChecker == other.forChecker &&
        this.attribution == other.attribution &&
        this.executionCondition == other.executionCondition
}

fun assertRequiredSelectionSetEquals(
    expected: RequiredSelectionSet?,
    actual: RequiredSelectionSet?
) {
    if (expected == null && actual == null) return
    if (expected == null) throw AssertionError("Expected null but got: $actual")
    if (actual == null) throw AssertionError("Expected $expected but got null")

    if (!actual.isEqualTo(expected)) {
        throw AssertionError(
            """
            RequiredSelectionSets are not equal:
            Expected: ${formatRequiredSelectionSet(expected)}
            Actual: ${formatRequiredSelectionSet(actual)}
            """.trimIndent()
        )
    }
}

fun assertRequiredSelectionSetListEquals(
    expected: List<RequiredSelectionSet>,
    actual: List<RequiredSelectionSet>
) {
    if (actual.size != expected.size) {
        throw AssertionError("List sizes differ: expected ${expected.size} but got ${actual.size}")
    }

    expected.zip(actual).forEachIndexed { index, (expectedRss, actualRss) ->
        try {
            assertRequiredSelectionSetEquals(expectedRss, actualRss)
        } catch (e: AssertionError) {
            throw AssertionError("RequiredSelectionSets differ at index $index: ${e.message}", e)
        }
    }
}

private fun formatRequiredSelectionSet(rss: RequiredSelectionSet): String {
    return """
        RequiredSelectionSet(
            selections.typeName=${rss.selections.typeName},
            selections=${AstPrinter.printAst(rss.selections.selections)},
            variablesResolvers=${rss.variablesResolvers},
            forChecker=${rss.forChecker},
            attribution=${rss.attribution},
            executionCondition=${rss.executionCondition}
        )
        """.trimIndent()
}
