@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.propertytest

import java.nio.file.Files
import java.nio.file.Path
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_CASE_ID_PROPERTY
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_CASE_MARKER_PROPERTY

/** One stored case as `<suite>/<batch>/<query>`; a batch is stipulated and holds one registry, and a query is a content ID within its batch. */
data class ResolverTestCaseId(
    val suite: String,
    val batch: String,
    val query: String,
) {
    init {
        require(listOf(suite, batch, query).all { part -> part.isNotBlank() && '/' !in part }) {
            "Resolver test case ID parts must be non-blank and contain no '/': $suite, $batch, $query"
        }
    }

    override fun toString(): String = "$suite/$batch/$query"

    companion object {
        fun parse(value: String): ResolverTestCaseId {
            val parts = value.split('/')
            require(parts.size == 3) {
                "$RESOLVER_TEST_CASE_ID_PROPERTY must have suite/batch/query form: $value"
            }
            return ResolverTestCaseId(parts[0], parts[1], parts[2])
        }
    }
}

fun configuredResolverTestCaseId(): ResolverTestCaseId? = System.getProperty(RESOLVER_TEST_CASE_ID_PROPERTY)?.let(ResolverTestCaseId::parse)

// Lets the resolverTestCase task fail when its class filter excludes the selected case's suite instead of passing with nothing run.
internal fun markResolverTestCaseRan(selected: ResolverTestCaseId) {
    System.getProperty(RESOLVER_TEST_CASE_MARKER_PROPERTY)?.let { marker -> Files.writeString(Path.of(marker), "$selected\n") }
}
