package viaduct.engine.runtime.validation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime.validation.Validator.Companion.flatten

class ValidatorTest {
    private val anyTestCases: List<Any?> = listOf(
        null,
        IllegalArgumentException(),
        "",
        "string",
        0,
        42,
        Validator
    )

    @Test
    fun `Unvalidated`() {
        anyTestCases.forEach {
            assertDoesNotThrow {
                Validator.Unvalidated.validate(it)
            }
        }
    }

    @Test
    fun `Invalid`() {
        anyTestCases.forEach {
            assertThrows<IllegalArgumentException> {
                Validator.Invalid.validate(it)
            }
        }
    }

    @Test
    fun `flatten`() {
        class MockValidator : Validator<Int> {
            var seen: Int? = null

            override fun validate(t: Int) {
                seen = t
            }
        }

        val validators = buildList<MockValidator>(10) { this += MockValidator() }
        validators.flatten().validate(42)

        assertTrue(validators.all { it.seen == 42 })
    }

    @Test
    fun `flatten -- first failure`() {
        // many validators that all pass
        assertDoesNotThrow {
            buildList<Validator<Int>>(10) { this += Validator.Unvalidated }
                .flatten()
                .validate(0)
        }

        // many validators that fail
        assertThrows<IllegalArgumentException> {
            buildList<Validator<Int>>(10) { this += Validator.Invalid }
                .flatten()
                .validate(0)
        }

        // a single validator that fails
        assertThrows<IllegalArgumentException> {
            buildList<Validator<Int>>(10) { this += Validator.Unvalidated } +
                listOf(Validator.Invalid)
                    .flatten()
                    .validate(0)
        }
    }

    @Test
    fun `flatten -- empty`() {
        val validators = emptyList<Validator<Int>>()
        val flattened = validators.flatten()
        assertDoesNotThrow {
            flattened.validate(42)
        }
    }
}
