package viaduct.engine.runtime2.execution

import graphql.ExecutionResult
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.runtime2.execution.testing.runQPlanFeatureTest
import viaduct.graphql.Scalars

/** Exercises scalar coercion through the service, executor, and GraphQL completion boundaries. */
class ScalarFeatureTest {
    data class ScalarCase(
        val name: String,
        val literal: String,
        val variable: Any,
        val input: Any,
        val output: Any = input,
        val serialized: Any = output,
    ) {
        override fun toString(): String = name
    }

    companion object {
        @JvmStatic
        fun scalars(): Stream<ScalarCase> =
            Stream.of(
                ScalarCase("Int", "42", 42, 42),
                ScalarCase("Float", "1.25", 1.25, 1.25),
                ScalarCase("String", "\"hello\"", "hello", "hello"),
                ScalarCase("Boolean", "true", true, true),
                ScalarCase("ID", "\"123\"", "123", "123"),
                ScalarCase("Byte", "127", 127, 127.toByte()),
                ScalarCase("Short", "32767", 32767, 32767.toShort()),
                ScalarCase("Long", "9223372036854775807", "9223372036854775807", Long.MAX_VALUE, serialized = Long.MAX_VALUE.toString()),
                ScalarCase("BigInteger", "123456789012345678901234567890", "123456789012345678901234567890", BigInteger("123456789012345678901234567890")),
                ScalarCase("BigDecimal", "123.45678901234567890123456789", "123.45678901234567890123456789", BigDecimal("123.45678901234567890123456789")),
                ScalarCase("Date", "\"2024-10-29\"", "2024-10-29", LocalDate.parse("2024-10-29"), serialized = "2024-10-29"),
                ScalarCase(
                    "DateTime",
                    "\"2024-10-29T14:30:00Z\"",
                    "2024-10-29T14:30:00Z",
                    Instant.parse("2024-10-29T14:30:00Z"),
                    serialized = "2024-10-29T14:30:00.000Z",
                ),
                ScalarCase(
                    "JSON",
                    """{key: "value", nested: [true, {date: "2024-10-29", optional: null}]}""",
                    mapOf("key" to "value", "nested" to listOf(true, mapOf("date" to "2024-10-29", "optional" to null))),
                    mapOf("key" to "value", "nested" to listOf(true, mapOf("date" to "2024-10-29", "optional" to null))),
                ),
            )
    }

    @Test
    fun `covers every supported framework scalar`() {
        assertEquals(
            Scalars.viaductStandardScalars.map { it.name }.toSet() - setOf("BackingData", "Time"),
            scalars().map { it.name }.toList().toSet() - setOf("Int", "Float", "String", "Boolean", "ID"),
        )
    }

    @ParameterizedTest(name = "{0} output and required selection")
    @MethodSource("scalars")
    fun `serializes outputs and preserves values in required selections`(scalar: ScalarCase) {
        EngineTestModule(
            """
            extend type Query { value: ${scalar.name}!, observed: Boolean! }
            """.trimIndent(),
        ) {
            fieldWithValue("Query" to "value", scalar.output)
            field("Query" to "observed") {
                resolver {
                    objectSelections("value")
                    fn { _, obj, _, _, _ ->
                        assertEquals(scalar.output, obj.get("value"))
                        true
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ value observed }").assertData(mapOf("value" to scalar.serialized, "observed" to true))
        }
    }

    @ParameterizedTest(name = "{0} literal and variable inputs")
    @MethodSource("scalars")
    fun `coerces literal and variable arguments`(scalar: ScalarCase) {
        echoModule(scalar).runQPlanFeatureTest {
            runQuery("{ echo(value: ${scalar.literal}) }").assertData(mapOf("echo" to scalar.serialized))
            runQuery(
                "query(${'$'}value: ${scalar.name}!) { echo(value: ${'$'}value) }",
                mapOf("value" to scalar.variable),
            ).assertData(mapOf("echo" to scalar.serialized))
        }
    }

    @ParameterizedTest(name = "{0} source output coercion")
    @MethodSource("scalars")
    fun `coerces source outputs while preserving JVM values in required selections`(scalar: ScalarCase) {
        val sourceValue = when (scalar.name) {
            "Int" -> 42L
            "Float" -> BigDecimal("1.25")
            "String" -> StringBuilder("hello")
            "Boolean" -> "true"
            "ID" -> 123L
            "DateTime" -> OffsetDateTime.parse("2024-10-29T16:30:00+02:00")
            else -> scalar.variable
        }
        EngineTestModule("extend type Query { value: ${scalar.name}!, observed: Boolean! }") {
            fieldWithValue("Query" to "value", sourceValue)
            field("Query" to "observed") {
                resolver {
                    objectSelections("value")
                    fn { _, obj, _, _, _ ->
                        val value = obj.get("value")
                        assertEquals(scalar.output, value)
                        assertEquals(scalar.output.javaClass, value!!.javaClass)
                        true
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ value observed }").assertData(mapOf("value" to scalar.serialized, "observed" to true))
        }
    }

    @ParameterizedTest(name = "{0} defaults and input objects")
    @MethodSource("scalars")
    fun `coerces schema and operation defaults inside input objects`(scalar: ScalarCase) {
        EngineTestModule(
            """
            input Input { value: ${scalar.name}! = ${scalar.literal} }
            extend type Query { echo(input: Input! = {}): ${scalar.name}! }
            """.trimIndent(),
        ) {
            field("Query" to "echo") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val value = (args.getValue("input") as Map<*, *>)["value"]
                        assertEquals(scalar.input, value)
                        value
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ echo }").assertData(mapOf("echo" to scalar.serialized))
            runQuery("query(${'$'}input: Input! = {}) { echo(input: ${'$'}input) }")
                .assertData(mapOf("echo" to scalar.serialized))
            runQuery("{ echo(input: {value: ${scalar.literal}}) }")
                .assertData(mapOf("echo" to scalar.serialized))
        }
    }

    @ParameterizedTest(name = "{0} lists and null")
    @MethodSource("scalars")
    fun `preserves scalar list elements and nulls`(scalar: ScalarCase) {
        EngineTestModule("extend type Query { echo(values: [${scalar.name}]!): [${scalar.name}]! }") {
            field("Query" to "echo") {
                resolver {
                    fn { args, _, _, _, _ ->
                        assertEquals(listOf(scalar.input, null), args.getValue("values"))
                        args.getValue("values")
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ echo(values: [${scalar.literal}, null]) }")
                .assertData(mapOf("echo" to listOf(scalar.serialized, null)))
            runQuery("query(${'$'}values: [${scalar.name}]!) { echo(values: ${'$'}values) }", mapOf("values" to listOf(scalar.variable, null)))
                .assertData(mapOf("echo" to listOf(scalar.serialized, null)))
        }
    }

    @ParameterizedTest(name = "{0} resolver variables")
    @MethodSource("scalars")
    fun `passes scalars through resolver variable providers`(scalar: ScalarCase) {
        EngineTestModule("extend type Query { echo(value: ${scalar.name}!): ${scalar.name}!, observed: Boolean! }") {
            field("Query" to "echo") {
                resolver { fn { args, _, _, _, _ -> args.getValue("value") } }
            }
            field("Query" to "observed") {
                resolver {
                    objectSelections("echo(value: ${'$'}value)") {
                        variables("value") { _, _ -> mapOf("value" to scalar.input) }
                    }
                    fn { _, obj, _, _, _ ->
                        assertEquals(scalar.input, obj.get("echo"))
                        true
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ observed }").assertJson("{data: {observed: true}}")
        }
    }

    @ParameterizedTest(name = "{0} nullable scalar")
    @MethodSource("scalars")
    fun `preserves null scalar values`(scalar: ScalarCase) {
        EngineTestModule("extend type Query { echo(value: ${scalar.name}): ${scalar.name} }") {
            field("Query" to "echo") { resolver { fn { args, _, _, _, _ -> args["value"] } } }
        }.runQPlanFeatureTest {
            runQuery("{ echo }").assertJson("{data: {echo: null}}")
            runQuery("{ echo(value: null) }").assertJson("{data: {echo: null}}")
            runQuery("query(${'$'}value: ${scalar.name}) { echo(value: ${'$'}value) }", mapOf("value" to null))
                .assertJson("{data: {echo: null}}")
        }
    }

    @Test
    fun `DateTime normalizes offsets to Instant in arguments and resolver reads`() {
        val timestamp = "2024-10-29T16:30:00+02:00"
        val offsetDateTime = OffsetDateTime.parse(timestamp)
        val instant = Instant.parse("2024-10-29T14:30:00Z")
        val serialized = "2024-10-29T14:30:00.000Z"
        EngineTestModule(
            """
            extend type Query {
              echo(value: DateTime! = "$timestamp"): DateTime!
              source: DateTime!
              observed: Boolean!
            }
            """.trimIndent(),
        ) {
            fieldWithValue("Query" to "source", instant)
            field("Query" to "echo") {
                resolver {
                    fn { args, _, _, _, _ ->
                        assertEquals(instant, args.getValue("value"))
                        args.getValue("value")
                    }
                }
            }
            field("Query" to "observed") {
                resolver {
                    objectSelections("source echo(value: ${'$'}value)") {
                        variables("value") { _, _ -> mapOf("value" to instant) }
                    }
                    fn { _, obj, _, _, _ ->
                        assertEquals(instant, obj.get("source"))
                        assertEquals(instant, obj.get("echo"))
                        true
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ echo source observed }")
                .assertData(mapOf("echo" to serialized, "source" to serialized, "observed" to true))
            runQuery("{ echo(value: \"$timestamp\") }").assertData(mapOf("echo" to serialized))
            for (value in listOf(timestamp, offsetDateTime)) {
                runQuery("query(${'$'}value: DateTime!) { echo(value: ${'$'}value) }", mapOf("value" to value))
                    .assertData(mapOf("echo" to serialized))
            }
        }
    }

    @Test
    fun `DateTime preserves native Instant values across Engine API boundaries`() {
        val instant = Instant.parse("2024-10-29T14:30:00Z")
        EngineTestModule("extend type Query { source: DateTime!, echo(value: DateTime!): DateTime!, observed: Boolean! }") {
            fieldWithValue("Query" to "source", instant)
            field("Query" to "echo") {
                resolver {
                    fn { args, _, _, _, _ ->
                        assertEquals(instant, args.getValue("value"))
                        args.getValue("value")
                    }
                }
            }
            field("Query" to "observed") {
                resolver {
                    objectSelections("source echo(value: ${'$'}value)") {
                        variables("value") { _, _ -> mapOf("value" to instant) }
                    }
                    fn { _, obj, _, _, _ ->
                        assertEquals(instant, obj.get("source"))
                        assertEquals(instant, obj.get("echo"))
                        true
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("query(${'$'}value: DateTime!) { source echo(value: ${'$'}value) observed }", mapOf("value" to instant))
                .assertData(mapOf("source" to "2024-10-29T14:30:00.000Z", "echo" to "2024-10-29T14:30:00.000Z", "observed" to true))
        }
    }

    @Test
    fun `JSON literals remain scalar values when sibling arguments use resolver variables`() {
        EngineTestModule("extend type Query { echo(value: JSON!, extra: Int!): JSON!, observed: JSON! }") {
            field("Query" to "echo") {
                resolver {
                    fn { args, _, _, _, _ ->
                        assertEquals(42, args.getValue("extra"))
                        args.getValue("value")
                    }
                }
            }
            field("Query" to "observed") {
                resolver {
                    objectSelections("echo(value: {nested: [true, {value: null}]}, extra: ${'$'}extra)") {
                        variables("extra") { _, _ -> mapOf("extra" to 42) }
                    }
                    fn { _, obj, _, _, _ -> obj.get("echo") }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ observed }").assertJson("{data: {observed: {nested: [true, {value: null}]}}}")
        }
    }

    @Test
    fun `invalid scalar outputs retain error paths and null propagation`() {
        EngineTestModule("extend type Query { invalid: Byte, required: Byte!, valid: Long! }") {
            fieldWithValue("Query" to "invalid", 128)
            fieldWithValue("Query" to "required", 128)
            fieldWithValue("Query" to "valid", Long.MAX_VALUE)
        }.runQPlanFeatureTest {
            val nullable = runQuery("{ bad: invalid valid }")
            assertEquals(mapOf("bad" to null, "valid" to Long.MAX_VALUE.toString()), nullable.getData())
            assertEquals(listOf(listOf("bad")), nullable.errors.map { it.path })
            val required = runQuery("{ required valid }")
            assertEquals(null, required.getData<Any?>())
            assertTrue(required.errors.any { it.path == listOf("required") })
        }
    }

    @Test
    fun `invalid scalar inputs are rejected before resolver invocation`() {
        var calls = 0
        EngineTestModule("extend type Query { echo(value: Long!): Long! }") {
            field("Query" to "echo") {
                resolver {
                    fn { args, _, _, _, _ ->
                        calls++
                        args.getValue("value")
                    }
                }
            }
        }.runQPlanFeatureTest {
            assertTrue(runQuery("{ echo(value: 9223372036854775808) }").errors.isNotEmpty())
            assertTrue(runQuery("query(${'$'}value: Long!) { echo(value: ${'$'}value) }", mapOf("value" to "9223372036854775808")).errors.isNotEmpty())
        }
        assertEquals(0, calls)
    }

    @Test
    fun `JSON supports all recursive value shapes and JVM numbers`() {
        val values = listOf(
            null,
            "text",
            true,
            1,
            2L,
            1.5f,
            2.5,
            BigInteger("12345678901234567890"),
            BigDecimal("1.234567890123456789"),
            mapOf("a" to mapOf("b" to 1)),
            listOf(1, mapOf("a" to 2), null),
            listOf(listOf(1, 2), null),
        )
        EngineTestModule("extend type Query { echo(value: JSON): JSON }") {
            field("Query" to "echo") { resolver { fn { args, _, _, _, _ -> args["value"] } } }
        }.runQPlanFeatureTest {
            values.forEach { value ->
                val result = runQuery("query(${'$'}value: JSON) { echo(value: ${'$'}value) }", mapOf("value" to value))
                assertTrue(result.errors.isEmpty(), result.errors.toString())
                assertEquals(mapOf("echo" to value), result.getData())
            }
            runQuery("{ echo(value: [1, {a: 2, b: null}]) }").assertData(mapOf("echo" to listOf(BigInteger.ONE, mapOf("a" to BigInteger.TWO, "b" to null))))
        }
    }

    @Test
    fun `JSON outputs preserve arbitrary objects without string conversion`() {
        val value = Any()
        EngineTestModule("extend type Query { value: JSON! }") {
            fieldWithValue("Query" to "value", value)
        }.runQPlanFeatureTest {
            val result = runQuery("{ value }")
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertSame(value, result.getData<Map<String, Any>>().getValue("value"))
        }
    }

    @Test
    fun `BackingData passes opaque values to required selections without serialization`() {
        val backing = Any()
        EngineTestModule(
            """
            extend type Query {
              backing: BackingData @backingData(class: "java.lang.Object")
              observed: Boolean!
            }
            """.trimIndent(),
        ) {
            fieldWithValue("Query" to "backing", backing)
            field("Query" to "observed") {
                resolver {
                    objectSelections("backing")
                    fn { _, obj, _, _, _ ->
                        assertSame(backing, obj.get("backing"))
                        true
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ observed }").assertJson("{data: {observed: true}}")
        }
    }

    private fun ExecutionResult.assertData(expected: Map<String, Any?>) {
        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(expected, getData<Map<String, Any?>>())
    }

    private fun echoModule(scalar: ScalarCase): EngineTestModule =
        EngineTestModule("extend type Query { echo(value: ${scalar.name}!): ${scalar.name}! }") {
            field("Query" to "echo") {
                resolver {
                    fn { args, _, _, _, _ ->
                        assertEquals(scalar.input, args.getValue("value"))
                        assertEquals(scalar.input.javaClass, args.getValue("value")!!.javaClass)
                        args.getValue("value")
                    }
                }
            }
        }
}
