package com.example.viadapp

import com.example.viadapp.production.DEFAULT_SCHEMA
import io.micronaut.context.ApplicationContext
import viaduct.service.api.ExecutionInput
import viaduct.service.api.Viaduct

/**
 * Minimal runnable entry point.
 *
 * Its job is to make this module packageable via the Gradle `application` plugin so it can be
 * exercised through `installDist` (see the `smokeTestDist` task in `build.gradle.kts`): it boots
 * the Micronaut context exactly like the tests, runs one query end-to-end, prints the result and
 * exits. Running the *packaged distribution* (rather than in-process classes) is what surfaces
 * packaging/classpath regressions that an in-process test cannot catch.
 */
fun main() {
    ApplicationContext.run().use { context ->
        val viaduct = context.getBean(Viaduct::class.java)
        val result =
            viaduct.executeAsync(
                ExecutionInput.create(operationText = "query { greeting }"),
                DEFAULT_SCHEMA.schemaId,
            ).join()
        check(result.errors.isEmpty()) { "Smoke query returned errors: ${result.errors}" }
        println(result.getData())
    }
}
