package com.example.execution.twoproject

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import viaduct.service.BasicViaductFactory
import viaduct.service.api.ExecutionInput

class PluginExecutionSmokeTest {
    @Test
    fun queriesAndMutationsExecuteThroughViaduct() {
        val viaduct = BasicViaductFactory.create()

        val queryResult = viaduct.execute(
            ExecutionInput.create("query { greeting author }")
        )
        assertTrue(queryResult.errors.isEmpty(), "Expected query execution without errors: ${queryResult.errors}")
        assertEquals(
            mapOf(
                "greeting" to "hello from two-project",
                "author" to "gradletestapps",
            ),
            queryResult.getData(),
        )

        val mutationResult = viaduct.execute(
            ExecutionInput.create("""mutation { echo(message: "plugin e2e") }""")
        )
        assertTrue(mutationResult.errors.isEmpty(), "Expected mutation execution without errors: ${mutationResult.errors}")
        assertEquals(mapOf("echo" to "plugin e2e"), mutationResult.getData())
    }

    @Test
    fun invalidFieldProducesValidationError() {
        val viaduct = BasicViaductFactory.create()

        val result = viaduct.execute(
            ExecutionInput.create("query { notAField }")
        )

        assertNull(result.getData())
        assertTrue(result.errors.isNotEmpty(), "Expected validation errors for undefined field")
        assertContains(result.errors.first().message, "notAField")
    }
}
