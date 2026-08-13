package viaduct.tenant.codegen.ksp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RegistryExtractorLoggerExtensionsTest {
    @Test
    fun `logging formats placeholders`() {
        val logger = RecordingKspLogger()

        logger.loggingRegistryExtractor("Hello {}, {}", "A", "B")

        assertEquals(
            "[RegistryExtractor] Hello A, B",
            logger.loggings.single(),
        )
    }

    @Test
    fun `warn preserves unmatched placeholders`() {
        val logger = RecordingKspLogger()

        logger.warnRegistryExtractor("Hello {} {} {}", "A")

        assertEquals(
            "[RegistryExtractor] Hello A {} {}",
            logger.warns.single(),
        )
    }

    @Test
    fun `error appends extra args`() {
        val logger = RecordingKspLogger()

        logger.errorRegistryExtractor("Only {}", "one", "two", "three")

        assertEquals(
            "[RegistryExtractor] Only one [extra args: two, three]",
            logger.errors.single(),
        )
    }

    @Test
    fun `logging renders null values`() {
        val logger = RecordingKspLogger()

        logger.loggingRegistryExtractor("Value is {}", null)

        assertEquals(
            "[RegistryExtractor] Value is null",
            logger.loggings.single(),
        )
    }

    @Test
    fun `error renders throwable with type and message`() {
        val logger = RecordingKspLogger()

        logger.errorRegistryExtractor("Failure: {}", IllegalStateException("boom"))

        assertEquals(
            "[RegistryExtractor] Failure: java.lang.IllegalStateException: boom",
            logger.errors.single(),
        )
    }
}
