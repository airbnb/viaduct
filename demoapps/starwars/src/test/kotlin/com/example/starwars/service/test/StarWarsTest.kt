package com.example.starwars.service.test

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest

/**
 * Basic test to verify that the Spring application context loads successfully.
 */
@SpringBootTest
class StarWarsTest : StringSpec({
    "should start application context" {
        true shouldBe true
    }
})
