package viaduct.tenant.runtime.featuretests

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.instrumentation.TaggedMetricInstrumentation
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder

@OptIn(ExperimentalCoroutinesApi::class)
class TaggedMetricInstrumentationFeatureTest {
    @Test
    fun `ensure execution is recorded`() {
        val registry = SimpleMeterRegistry()
        FeatureTestBuilder("extend type Query { a: Int }", meterRegistry = registry)
            .resolver("Query" to "a") { 1 }
            .build()
            .assertJson("{data: {a: 1}}", "{ a }")

        val executionMeter = registry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_EXECUTION_METER_NAME }
        assertNotNull(executionMeter)
        assertEquals("true", executionMeter.id.tags.find { it.key == "success" }?.value)
    }

    @Test
    fun `ensure operation execution operation name tag is recorded if specified`() {
        val registry = SimpleMeterRegistry()
        FeatureTestBuilder("extend type Query { a: Int }", meterRegistry = registry)
            .resolver("Query" to "a") { 1 }
            .build()
            .execute("query testQuery { a }", operationName = "testQuery")

        val executionMeter = registry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_EXECUTION_METER_NAME }
        assertNotNull(executionMeter)
        assertEquals("true", executionMeter.id.tags.find { it.key == "success" }?.value)

        val operationNameTag = executionMeter.id.tags.find { it.key == "operation_name" }
        assertNotNull(operationNameTag)
        assertEquals("testQuery", operationNameTag.value)
    }

    @Test
    fun `ensure validation error will be tagged as failure`() {
        val registry = SimpleMeterRegistry()
        FeatureTestBuilder("extend type Query { a: Int }", meterRegistry = registry)
            .resolver("Query" to "a") { 1 }
            .build()
            .execute("{")

        val operationMeter = registry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_EXECUTION_METER_NAME }
        assertNotNull(operationMeter)
        assertEquals("false", operationMeter.id.tags.find { it.key == "success" }?.value)
    }

    @Test
    fun `ensure execution count is correct`() {
        val registry = SimpleMeterRegistry()
        var counter = 0

        val viaduct = FeatureTestBuilder("extend type Query { a: Int }", meterRegistry = registry)
            .resolver("Query" to "a") { counter++ }
            .build()

        viaduct.assertJson("{data: {a: 0}}", "{ a }")
        viaduct.assertJson("{data: {a: 1}}", "{ a }")
        viaduct.assertJson("{data: {a: 2}}", "{ a }")
        viaduct.execute("{") // this one should fail

        val successMeter = registry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_EXECUTION_METER_NAME && it.id.tags.find { it.key == "success" }?.value == "true" }
        assertEquals(3.0, successMeter?.measure()?.find { it.statistic.name == "COUNT" }?.value)

        val failedMeter = registry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_EXECUTION_METER_NAME && it.id.tags.find { it.key == "success" }?.value == "false" }
        assertEquals(1.0, failedMeter?.measure()?.find { it.statistic.name == "COUNT" }?.value)
    }

    @Test
    fun `ensure detailed percentile is recorded`() {
        val registry = SimpleMeterRegistry()
        var counter = 0

        val viaduct = FeatureTestBuilder("extend type Query { a: Int }", meterRegistry = registry)
            .resolver("Query" to "a") { counter++ }
            .build()

        viaduct.assertJson("{data: {a: 0}}", "{ a }")
        viaduct.assertJson("{data: {a: 1}}", "{ a }")
        viaduct.assertJson("{data: {a: 2}}", "{ a }")

        val executionPercentiles = registry.meters.filter { it.id.name == (TaggedMetricInstrumentation.VIADUCT_EXECUTION_METER_NAME + ".percentile") }
            .map { it.id.tags.first { it.key == "phi" }.value.toDouble() }
            .toSet()

        assertEquals(setOf(0.5, 0.75, 0.9, 0.95), executionPercentiles)

        val fieldPercentile = registry.meters.filter { it.id.name == (TaggedMetricInstrumentation.VIADUCT_FIELD_METER_NAME + ".percentile") }
            .map { it.id.tags.first { it.key == "phi" }.value.toDouble() }
            .toSet()

        assertEquals(setOf(0.5, 0.75, 0.9, 0.95), fieldPercentile)
    }

    @Test
    fun `ensure field tags are present`() {
        val registry = SimpleMeterRegistry()

        FeatureTestBuilder("extend type Query { a: Int }", meterRegistry = registry)
            .resolver("Query" to "a") { 1 }
            .build()
            .assertJson("{data: {a: 1}}", "query testQuery { a }")

        val fieldMeter = registry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_FIELD_METER_NAME }
        assertNotNull(fieldMeter)
        assertEquals("testQuery", fieldMeter.id.tags.find { it.key == "operation_name" }?.value)
        assertEquals("Query.a", fieldMeter.id.tags.find { it.key == "field" }?.value)
        assertEquals("true", fieldMeter.id.tags.find { it.key == "success" }?.value)
    }

    @Test
    fun `ensure field failure tag is present`() {
        val registry = SimpleMeterRegistry()

        FeatureTestBuilder("extend type Query { a: Int }", meterRegistry = registry)
            .resolver("Query" to "a") { throw RuntimeException("test exception") }
            .build()
            .execute("query testQuery { a }")

        val fieldMeter = registry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_FIELD_METER_NAME }
        assertNotNull(fieldMeter)
        assertEquals("testQuery", fieldMeter.id.tags.find { it.key == "operation_name" }?.value)
        assertEquals("Query.a", fieldMeter.id.tags.find { it.key == "field" }?.value)
        assertEquals("false", fieldMeter.id.tags.find { it.key == "success" }?.value)
    }

    @Test
    fun `ensure field count is correct`() {
        val registry = SimpleMeterRegistry()

        val viaduct = FeatureTestBuilder("extend type Query { a: Int, b: Int, c: Int }", meterRegistry = registry)
            .resolver("Query" to "a") { 1 }
            .resolver("Query" to "b") { 2 }
            .resolver("Query" to "c") { 3 }
            .build()

        viaduct.assertJson("{data: {a: 1}}", "{ a }")
        viaduct.assertJson("{data: {a: 1, b: 2}}", "{ a, b }")
        viaduct.assertJson("{data: {a: 1, b: 2, c: 3}}", "{ a, b, c }")

        val fieldToCountMap = registry.meters.filter { it.id.name == TaggedMetricInstrumentation.VIADUCT_FIELD_METER_NAME }
            .map { it.id.tags.find { tag -> tag.key == "field" }?.value to it.measure().find { measurement -> measurement.statistic.name == "COUNT" }?.value }
            .toMap()
        assertEquals(
            setOf(
                "Query.a",
                "Query.b",
                "Query.c"
            ),
            fieldToCountMap.keys
        )

        assertEquals(3.0, fieldToCountMap.get("Query.a"))
        assertEquals(2.0, fieldToCountMap.get("Query.b"))
        assertEquals(1.0, fieldToCountMap.get("Query.c"))
    }

    @Test
    fun `ensure operation metric is recorded`() {
        val registry = SimpleMeterRegistry()

        FeatureTestBuilder("extend type Query { a: Int }", meterRegistry = registry)
            .resolver("Query" to "a") { 1 }
            .build()
            .execute("query testQuery { a }")

        val meter = registry.meters.find { it.id.name == TaggedMetricInstrumentation.VIADUCT_OPERATION_METER_NAME }
        assertNotNull(meter)
        assertEquals("testQuery", meter.id.tags.find { it.key == "operation_name" }?.value)
    }
}
