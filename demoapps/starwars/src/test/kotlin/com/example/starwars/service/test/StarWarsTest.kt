package com.example.starwars.service.test

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micronaut.test.extensions.junit5.annotation.MicronautTest

/**
 * Basic test to verify that the Micronaut application context loads successfully.
 */
@MicronautTest
class StarWarsTest : StringSpec({
    "should start application context" {
        true shouldBe true
    }
})
