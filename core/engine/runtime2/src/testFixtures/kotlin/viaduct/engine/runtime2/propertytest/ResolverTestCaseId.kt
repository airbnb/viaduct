@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.propertytest

import java.nio.file.Files
import java.nio.file.Path
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_CASE_ID_PROPERTY
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_CASE_MARKER_PROPERTY

/** One stored case as `<suite>/<batch>/<registry>/<query>`; batches are stipulated, registries and queries are content IDs within their batch. */
data class ResolverTestCaseId(
    val suite: String,
    val batch: String,
    val registry: String,
    val query: String,
) {
    init {
        require(listOf(suite, batch, registry, query).all { part -> part.isNotBlank() && '/' !in part }) {
            "Resolver test case ID parts must be non-blank and contain no '/': $suite, $batch, $registry, $query"
        }
    }

    override fun toString(): String = "$suite/$batch/$registry/$query"

    companion object {
        fun parse(value: String): ResolverTestCaseId {
            val parts = value.split('/')
            require(parts.size == 4) {
                "$RESOLVER_TEST_CASE_ID_PROPERTY must have suite/batch/registry/query form: $value"
            }
            return ResolverTestCaseId(parts[0], parts[1], parts[2], parts[3])
        }
    }
}

fun configuredResolverTestCaseId(): ResolverTestCaseId? = System.getProperty(RESOLVER_TEST_CASE_ID_PROPERTY)?.let(ResolverTestCaseId::parse)

// Lets the resolverTestCase task fail when its class filter excludes the selected case's suite instead of passing with nothing run.
internal fun markResolverTestCaseRan(selected: ResolverTestCaseId) {
    System.getProperty(RESOLVER_TEST_CASE_MARKER_PROPERTY)?.let { marker -> Files.writeString(Path.of(marker), "$selected\n") }
}
