package viaduct.engine.runtime.execution

import graphql.schema.DataFetcher
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.execution.ExecutionTestHelpers.executeViaductModernGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

/**
 * Unit tests for FieldCompleter's enum handling logic with schema version skew tolerance.
 *
 * These tests verify the behavior of completeValueForEnum method which was modified to handle
 * schema version skew scenarios during hotswap/MTD deployments. The key scenarios tested:
 *
 * 1. String enum values validated against runtime GraphQL schema
 * 2. Java Enum instances extracted and validated
 * 3. Invalid values rejected with proper error messages
 * 4. Version skew tolerance: runtime schema may have values unknown to compiled code
 */
@ExperimentalCoroutinesApi
class FieldCompleterEnumTest {
    enum class TestEnum {
        VALUE_A,
        VALUE_B
    }

    @Test
    fun `completeValueForEnum handles null enum value`() =
        runExecutionTest {
            val sdl = """
            type Query {
                enumField: TestEnum
            }

            enum TestEnum {
                VALUE_A
                VALUE_B
            }
            """.trimIndent()

            val resolvers = mapOf(
                "Query" to mapOf(
                    "enumField" to DataFetcher { null }
                )
            )

            val result = executeViaductModernGraphQL(
                sdl = sdl,
                resolvers = resolvers,
                query = "{ enumField }"
            )

            assertTrue(result.errors.isEmpty(), "Expected no errors but got: ${result.errors}")
            val data = result.getData<Map<String, Any?>>()
            assertNotNull(data)
            assertEquals(null, data["enumField"])
        }

    @Test
    fun `completeValueForEnum handles string value valid in runtime schema`() =
        runExecutionTest {
            val sdl = """
            type Query {
                enumField: TestEnum
            }

            enum TestEnum {
                VALUE_A
                VALUE_B
            }
            """.trimIndent()

            val resolvers = mapOf(
                "Query" to mapOf(
                    "enumField" to DataFetcher { "VALUE_A" }
                )
            )

            val result = executeViaductModernGraphQL(
                sdl = sdl,
                resolvers = resolvers,
                query = "{ enumField }"
            )

            assertTrue(result.errors.isEmpty(), "Expected no errors but got: ${result.errors}")
            val data = result.getData<Map<String, Any?>>()
            assertNotNull(data)
            assertEquals("VALUE_A", data["enumField"])
        }

    @Test
    fun `completeValueForEnum handles Java enum instance valid in runtime schema`() =
        runExecutionTest {
            val sdl = """
            type Query {
                enumField: TestEnum
            }

            enum TestEnum {
                VALUE_A
                VALUE_B
            }
            """.trimIndent()

            val resolvers = mapOf(
                "Query" to mapOf(
                    "enumField" to DataFetcher { TestEnum.VALUE_A }
                )
            )

            val result = executeViaductModernGraphQL(
                sdl = sdl,
                resolvers = resolvers,
                query = "{ enumField }"
            )

            assertTrue(result.errors.isEmpty(), "Expected no errors but got: ${result.errors}")
            val data = result.getData<Map<String, Any?>>()
            assertNotNull(data)
            assertEquals("VALUE_A", data["enumField"])
        }

    @Test
    fun `completeValueForEnum rejects string value invalid in runtime schema`() =
        runExecutionTest {
            val sdl = """
            type Query {
                enumField: TestEnum
            }

            enum TestEnum {
                VALUE_A
                VALUE_B
            }
            """.trimIndent()

            val resolvers = mapOf(
                "Query" to mapOf(
                    "enumField" to DataFetcher { "INVALID_VALUE" }
                )
            )

            val result = executeViaductModernGraphQL(
                sdl = sdl,
                resolvers = resolvers,
                query = "{ enumField }"
            )

            assertTrue(result.errors.isNotEmpty(), "Expected errors for invalid enum value")
            val error = result.errors[0]
            assertTrue(
                error.message.contains("Invalid enum value 'INVALID_VALUE' for type 'TestEnum'"),
                "Expected error message about invalid enum value, got: ${error.message}"
            )
        }

    @Test
    fun `completeValueForEnum handles schema version skew - new enum value in runtime schema`() =
        runExecutionTest {
            // Scenario: Runtime schema has VALUE_C, but compiled Java enum only has VALUE_A and VALUE_B
            // When resolver returns string "VALUE_C", it should be accepted because it's valid in runtime schema
            val sdl = """
            type Query {
                enumField: TestEnum
            }

            enum TestEnum {
                VALUE_A
                VALUE_B
                VALUE_C
            }
            """.trimIndent()

            val resolvers = mapOf(
                "Query" to mapOf(
                    // Returning string "VALUE_C" which exists in runtime schema
                    // but not in our TestEnum (simulating version skew)
                    "enumField" to DataFetcher { "VALUE_C" }
                )
            )

            val result = executeViaductModernGraphQL(
                sdl = sdl,
                resolvers = resolvers,
                query = "{ enumField }"
            )

            assertTrue(result.errors.isEmpty(), "Expected no errors for valid runtime schema value, got: ${result.errors}")
            val data = result.getData<Map<String, Any?>>()
            assertNotNull(data)
            assertEquals("VALUE_C", data["enumField"])
        }

    @Test
    fun `completeValueForEnum handles unexpected type with fallback serialization`() =
        runExecutionTest {
            val sdl = """
            type Query {
                enumField: TestEnum
            }

            enum TestEnum {
                VALUE_A
                VALUE_B
            }
            """.trimIndent()

            val resolvers = mapOf(
                "Query" to mapOf(
                    // Return an integer - GraphQL Java's serialize should handle or fail gracefully
                    "enumField" to DataFetcher { 42 }
                )
            )

            val result = executeViaductModernGraphQL(
                sdl = sdl,
                resolvers = resolvers,
                query = "{ enumField }"
            )

            // Should have an error as integer cannot be coerced to enum
            assertTrue(result.errors.isNotEmpty(), "Expected error for non-enum value")
        }

    @Test
    fun `completeValueForEnum handles non-null enum field with null value`() =
        runExecutionTest {
            val sdl = """
            type Query {
                enumField: TestEnum!
            }

            enum TestEnum {
                VALUE_A
                VALUE_B
            }
            """.trimIndent()

            val resolvers = mapOf(
                "Query" to mapOf(
                    "enumField" to DataFetcher { null }
                )
            )

            val result = executeViaductModernGraphQL(
                sdl = sdl,
                resolvers = resolvers,
                query = "{ enumField }"
            )

            // Should have an error for null value on non-null field
            assertTrue(result.errors.isNotEmpty(), "Expected error for null on non-null field")
            // The entire data should be null due to null bubbling
            assertEquals(null, result.getData<Any?>())
        }

    @Test
    fun `completeValueForEnum handles list of enum values`() =
        runExecutionTest {
            val sdl = """
            type Query {
                enumList: [TestEnum]
            }

            enum TestEnum {
                VALUE_A
                VALUE_B
            }
            """.trimIndent()

            val resolvers = mapOf(
                "Query" to mapOf(
                    "enumList" to DataFetcher { listOf("VALUE_A", TestEnum.VALUE_B, "VALUE_A") }
                )
            )

            val result = executeViaductModernGraphQL(
                sdl = sdl,
                resolvers = resolvers,
                query = "{ enumList }"
            )

            assertTrue(result.errors.isEmpty(), "Expected no errors but got: ${result.errors}")
            val data = result.getData<Map<String, Any?>>()
            assertNotNull(data)
            assertEquals(listOf("VALUE_A", "VALUE_B", "VALUE_A"), data["enumList"])
        }

    @Test
    fun `completeValueForEnum handles list with invalid enum value`() =
        runExecutionTest {
            val sdl = """
            type Query {
                enumList: [TestEnum]
            }

            enum TestEnum {
                VALUE_A
                VALUE_B
            }
            """.trimIndent()

            val resolvers = mapOf(
                "Query" to mapOf(
                    "enumList" to DataFetcher { listOf("VALUE_A", "INVALID", "VALUE_B") }
                )
            )

            val result = executeViaductModernGraphQL(
                sdl = sdl,
                resolvers = resolvers,
                query = "{ enumList }"
            )

            // Should have an error for the invalid enum value in the list
            assertTrue(result.errors.isNotEmpty(), "Expected error for invalid enum value in list")
            assertTrue(
                result.errors[0].message.contains("Invalid enum value 'INVALID'"),
                "Expected error about invalid enum value, got: ${result.errors[0].message}"
            )
        }
}
