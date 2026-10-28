package viaduct.engine.runtime2.execution.testing

import graphql.ExecutionResult
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Asserts the GraphQL response data and the paths of every expected error.
 */
fun ExecutionResult.assertResult(
    expectedData: Any?,
    vararg expectedErrorPaths: List<Any>,
) {
    assertEquals(expectedData, getData())
    assertEquals(
        expectedErrorPaths.toSet(),
        errors.mapTo(linkedSetOf()) { error -> assertNotNull(error.path) },
        errors.joinToString { error -> error.message },
    )
}
