package viaduct.graphql.test

import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AssertMatchesTest {
    private fun subject(actualValue: Any?) = mapOf<Any?, Any?>("subject" to actualValue)

    @Test
    fun `null pattern matches null value`() {
        subject(null).assertMatches({ "subject" to null })
    }

    @Test
    fun `null pattern does not match non-null value`() {
        val assertionFailures = subject("nope").findFailedMatches({ "subject" to null })

        // Neat-O self-referential use of code under test to run the test!
        // Match to a list of a single error message that mentioned "null" in it...
        subject(assertionFailures).assertMatches({ "subject" to listOf(".*null.*") })
    }

    @Test
    fun `string regex matches various types`() {
        // Test regex matching against numbers, strings, and booleans in a list
        val actual = subject(listOf(13420, "Hello!", true, false))
        actual.assertMatches({ "subject" to listOf("1[0-9]*0", ".*!", "true", "false") })
    }

    @Test
    fun `string regex fails against various types`() {
        // Test partial failures in a list - first two should fail, third should pass
        val actual = subject(listOf(999, "Goodbye", 42.5))
        val assertionFailures = actual.findFailedMatches({ "subject" to listOf("1[0-9]*0", ".*!", "[0-9]+\\.[0-9]+") })

        // Should have failures for the first two items with proper context paths
        subject(assertionFailures).assertMatches({
            "subject" to listOf(
                "subject\\.\\[0\\]:.*999.*",
                "subject\\.\\[1\\]:.*Goodbye.*"
            )
        })
    }

    @Test
    fun `map pattern matches nested values`() {
        // Test map matching with various leaf value types
        val actual = subject(mapOf("num" to 123, "text" to "test", "flag" to true))
        actual.assertMatches({
            "subject" to {
                "num" to "[0-9]+"
                "text" to ".*"
                "flag" to "true"
            }
        })
    }

    @Test
    fun `list of mixed maps with nested structure fails with context`() {
        // Test failure context paths in nested structures: list -> map -> nested map
        val actual = subject(
            listOf(
                mapOf("id" to 1, "name" to "Alice"),
                mapOf("id" to 2, "details" to mapOf("name" to "Bob", "age" to 30))
            )
        )

        val assertionFailures = actual.findFailedMatches({
            "subject" to arrayOf(
                {
                    "id" to "[0-9]+"
                    "name" to "Z.*" // Should fail - Alice doesn't start with Z
                },
                {
                    "id" to "[0-9]+"
                    "details" to {
                        "name" to "C.*" // Should fail - Bob doesn't start with C
                        "age" to "[0-9]+"
                    }
                }
            )
        })

        // Should have failures for the two name fields with proper context paths
        subject(assertionFailures).assertMatches({
            "subject" to listOf(
                "subject\\.\\[0\\]\\.name:.*Alice.*",
                "subject\\.\\[1\\]\\.details\\.name:.*Bob.*"
            )
        })
    }

    @Test
    fun `type mismatch scenarios fail as expected`() {
        val testCases = listOf(
            123, // Pattern expects string but gets number
            "hello", // Pattern expects map but gets string
            mapOf("key" to "value"), // Pattern expects list but gets map
            42 // Pattern expects null but gets non-null
        )

        val actual = subject(testCases)
        val assertionFailures = actual.findFailedMatches({
            "subject" to listOf(
                "string", // Expects string, gets number
                queryResultMap { "nested" to "map" }, // Expects map, gets string
                listOf("item"), // Expects list, gets map
                null // Expects null, gets non-null
            )
        })

        // Should have failures for all type mismatches
        subject(assertionFailures).assertMatches({
            "subject" to listOf(
                ".*123.*does not match.*",
                ".*Expecting a Map.*found a String.*",
                ".*Expecting a Iterable.*found a.*Map.*",
                ".*42.*null.*"
            )
        })
    }

    @Test
    fun `unsupported template type throws IllegalArgumentException`() {
        val actual = subject("test")

        val exception = assertThrows<IllegalArgumentException> {
            actual.findFailedMatches({
                "subject" to 42.5 // Double/Float is not a supported template type
            })
        }

        assertTrue(exception.message!!.contains("Template contained bad subtemplate"))
    }

    @Test
    fun `critical edge cases for pattern matching`() {
        val testCases = listOf(
            // Empty collections
            emptyMap<String, Any?>(),
            emptyList<Any?>(),
            // Map partial matching
            mapOf("a" to 1, "b" to 2, "extraField" to "should be ignored"), // extra fields OK
            mapOf("a" to 1), // missing required field (will fail)
            // List length mismatch
            listOf("one", "two"), // wrong length (will fail)
            // Regex vs null actual value
            null // null actual with regex pattern (will fail)
        )

        val actual = subject(testCases)
        val assertionFailures = actual.findFailedMatches({
            "subject" to listOf(
                queryResultMap { }, // Empty map pattern
                listOf<String>(), // Empty list pattern
                queryResultMap {
                    "a" to "[0-9]+"
                    "b" to "[0-9]+"
                }, // Partial match (extra field ignored)
                queryResultMap {
                    "a" to "[0-9]+"
                    "b" to "[0-9]+"
                }, // Missing field 'b'
                listOf(".*", ".*", ".*"), // Length mismatch (expects 3, gets 2)
                "[a-z]+" // Regex pattern with null actual
            )
        })

        // Should have failures for: missing field, length mismatch, and regex vs null
        subject(assertionFailures).assertMatches({
            "subject" to listOf(
                ".*missing field.*b.*", // Missing required field
                ".*not same length.*", // Length mismatch
                ".*Unexpected null value.*" // Regex vs null
            )
        })
    }
}
