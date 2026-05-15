package viaduct.utils.test

import org.junit.jupiter.api.Assertions.assertEquals

/** Normalize line endings to LF — makes string comparison more tolerant for cross-platform testing. */
fun String.lf(): String = replace("\r\n", "\n")

/**
 * assertEquals with line endings normalized to LF on both sides — makes string
 * comparison more tolerant for cross-platform testing (e.g., CRLF on Windows).
 */
fun assertEqualsLf(
    expected: String,
    actual: String,
    message: String? = null
) = assertEquals(expected.lf(), actual.lf(), message)
